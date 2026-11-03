import network.MonitorScheduler;
import storage.MetricsStore;
import web.DashboardServer;

import java.io.File;
import java.security.Security;

/**
 * NetPulse — HTTP Website Monitoring Tool
 * Entry point: disables JVM DNS cache and starts the monitoring services.
 *
 * Usage:
 *   java Main [port]
 *
 * Default port: 8080
 * Dashboard: http://localhost:8080
 * API:       http://localhost:8080/api/metrics
 */
public class Main {

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        // ── Step 1: Disable JVM DNS caching so InetAddress always resolves fresh ──
        Security.setProperty("networkaddress.cache.ttl", "0");
        Security.setProperty("networkaddress.cache.negative.ttl", "0");
        System.setProperty("sun.net.inetaddr.ttl", "0");

        System.out.println("  _   _      _   ____        _          ");
        System.out.println(" | \\ | | ___| |_|  _ \\ _   _| |___  ___ ");
        System.out.println(" |  \\| |/ _ \\ __| |_) | | | | / __|/ _ \\");
        System.out.println(" | |\\  |  __/ |_|  __/| |_| | \\__ \\  __/");
        System.out.println(" |_| \\_|\\___|\\__|_|    \\__,_|_|___/\\___|");
        System.out.println();
        System.out.println(" HTTP Website & Network Performance Monitor");
        System.out.println(" ──────────────────────────────────────────");

        // ── Step 2: Determine port ──
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[Config] Invalid port '" + args[0] + "', using default " + DEFAULT_PORT);
            }
        }

        // ── Step 3: Resolve paths relative to the project root ──
        String projectRoot = resolveProjectRoot();
        String dataDir = projectRoot + File.separator + "data";
        String publicDir = projectRoot + File.separator + "public";

        System.out.println("[Config] Project root: " + projectRoot);
        System.out.println("[Config] Data dir:     " + dataDir);
        System.out.println("[Config] Public dir:   " + publicDir);

        // ── Step 4: Initialize services ──
        MetricsStore metricsStore = new MetricsStore(dataDir);
        MonitorScheduler scheduler = new MonitorScheduler(metricsStore);
        DashboardServer dashboardServer = new DashboardServer(port, metricsStore, scheduler, publicDir);

        // ── Step 5: Add default monitoring targets ──
        scheduler.addTarget("https://httpbin.org/status/200", 10);
        scheduler.addTarget("https://api.github.com", 15);
        scheduler.addTarget("https://httpbin.org/status/503", 10);
        scheduler.addTarget("https://example.com", 30);

        // ── Step 6: Start the dashboard server ──
        try {
            dashboardServer.start();
        } catch (Exception e) {
            System.err.println("[FATAL] Failed to start dashboard server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // ── Step 7: Graceful shutdown hook ──
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Shutdown] Shutting down NetPulse...");
            scheduler.shutdown();
            dashboardServer.stop();
            System.out.println("[Shutdown] Goodbye.");
        }));
    }

    /**
     * Resolves the project root directory.
     * Works when run from the project root (HttpWebsiteMonitor/) or from src/.
     */
    private static String resolveProjectRoot() {
        // Check if we're running from the project root already
        File publicDir = new File("public");
        if (publicDir.exists() && publicDir.isDirectory()) {
            return System.getProperty("user.dir");
        }

        // Check parent directory (if running from src/ or a subdirectory)
        File parentPublic = new File(".." + File.separator + "public");
        if (parentPublic.exists() && parentPublic.isDirectory()) {
            return new File("..").getAbsolutePath();
        }

        // Fall back to current directory
        System.err.println("[Config] Warning: Could not find 'public/' directory. Using current directory.");
        return System.getProperty("user.dir");
    }
}

