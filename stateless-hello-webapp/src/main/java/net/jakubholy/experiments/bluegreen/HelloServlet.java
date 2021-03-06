package net.jakubholy.experiments.bluegreen;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/** A simple servlet responding to clients and handling (settable) health checks from HAProxy. */
@WebServlet(urlPatterns = {"/js/deployment-bar.js", "/health", "/health/*"})
public class HelloServlet extends HttpServlet {

    private static final Date INITIALIZED = new Date(); // reset upon reload/restart
    private static final String HEALTH_URL = "/health";
    private static final String HEALTH_DISABLE_URL = HEALTH_URL + "/disable";
    private static final String HEALTH_ENABLE_URL = HEALTH_URL + "/enable";

    private final AtomicBoolean newestVersion = new AtomicBoolean(true);

    public HelloServlet() {
        // Compare our zone with the current zone file and set newestVersion accordingly
        final File currentZoneFile= new File("/usr/local/lib/cs/current_zone");
        if (currentZoneFile.isFile() && currentZoneFile.canRead()) {

            try {
                final String zoneFromFile = new BufferedReader(new FileReader(currentZoneFile)).readLine();
                if (!zoneFromFile.equals(getZone())) {
                    newestVersion.set(false);
                }
            } catch (IOException ignored) {
                // ignore, assume this is the current version
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        if ("/js/deployment-bar.js".equals(request.getRequestURI())) {
            getDeploymentBarJs(request, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_NOT_FOUND, "Only " + HEALTH_URL + " are served via this servlet.");

    }

    private void getDeploymentBarJs(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException  {

        // Set session attributes - for testing that relaod doesn't break sessions
        final HttpSession session = request.getSession(true);
        Object sessionStart = session.getAttribute("sessionStart");
        if (sessionStart == null) {
            sessionStart = "now";
            session.setAttribute("sessionStart", new Date().toString());
        }
        // end session stuff

        // Response
        response.setContentType("text/javascript");
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Cache-Control", "public, max-age=0, no-cache"); // This is not always enough => we include the zone in the URL to make 2 unique URLs
        response.setHeader("Expires", "Sat, 26 Jul 1997 00:00:00 GMT");

        String zone = getZone();
        if (zone == null) {
            response.getWriter().println("alert('Application configuration ERROR: env (blue/green) not specified via a system property \\'zone\\' as expected');");
            zone = "undefined";
        }

        final String otherZone = zone.equals("blue")? "green" : "blue";

        final String versionMessage;
        final String otherVersionLabel;
        final String bgrColor;

        // The version of this build (date, time, git hash); the version is output into a MANIFEST.MF during the build
        // and accessed here. BEWARE: Will be null if not running the app from the package (i.e. via jetty:run); git
        // hash will be null if built on a system without git (e.g. inside the Vagrant VM)
        final String currentVersion = getClass().getPackage().getImplementationVersion();

        if (newestVersion.get()) {
            versionMessage = "You are running the newest version " + currentVersion;
            otherVersionLabel = "previous";
            bgrColor = "darksalmon";
        } else {
            versionMessage = "You are running an old version (" + currentVersion + "), consider switching";
            otherVersionLabel = "newest";
            bgrColor = "darkred";
        }

        // Note: the Path is important to avoid creating different cookies based on where in the app the user is
        final String swtichVersionUrl = "javascript:document.cookie=\"X-Force-Zone=" + otherZone + "; Path=/\";document.location.reload(true);false";

        final String jsBarHtml =
                "<div id='deploymentBar' style='background-color:" + bgrColor + ";position:absolute;top:0px;left:0px;width:100%;'>" +
                versionMessage +
                "<span style='float:right'>[<a href='" + swtichVersionUrl + "'>Switch to the " + otherVersionLabel + " version</a>]" +
                " <span style='color:" + zone + "'>&#x25CF;</span></span>" +
                "</div>";

        final String barCreationJs = "var onloadOld=window.onload;window.onload=(function(){\n" +
                "var body=document.getElementsByTagName('body')[0];\n" +
                "var elm=document.createElement('div');\n" +
                "elm.innerHTML='" + jsBarHtml.replaceAll("'", "\\\\'") +"';\n" +
                "var jsDiv=elm.firstChild;\n" +
                "body.insertBefore(jsDiv, body.firstChild);\n" +
                "if (onloadOld) onloadOld();\n" +
                "});";

        response.getWriter().println(barCreationJs);
    }

    /** The name of the deployment zone this app is deployed to (blue or green). */
    public static String getZone() {
        return System.getProperty("zone");
    }


    /**
     * HEAD request to /health is used by HAProxy to check the availability of the server.
     */
    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (HEALTH_URL.equals(req.getRequestURI())) {
            final int status = newestVersion.get()? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE;
            resp.setStatus(status);
        } else {
            super.doHead(req, resp);
        }
    }

    /**
     * Post to /health/disable for /health to start responding with an error code to HAProxy's requests,
     * to /health/enable to make it return OK again.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (HEALTH_DISABLE_URL.equals(req.getRequestURI())) {
            boolean wasEnabled = newestVersion.getAndSet(false);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println(wasEnabled? "DISABLING the server" : "NoOp, already disabled");
        } else if (HEALTH_ENABLE_URL.equals(req.getRequestURI())) {
            boolean wasEnabled = newestVersion.getAndSet(true);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println(wasEnabled? "NoOp, already enabled" : "ENABLING the server");
        } else {
            super.doPost(req, resp);
        }
    }
}
