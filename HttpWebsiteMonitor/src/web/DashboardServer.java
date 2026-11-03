package web;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import network.MonitorScheduler;
import storage.MetricsStore;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Embedded HTTP server built on com.sun.net.httpserver.HttpServer.
 * Serves the single-page dashboard UI (index.html) and provides a
 * REST API at /api/metrics for live monitoring data.
 *
 * Endpoints:
 *   GET  /             → serves public/index.html
 *   GET  /api/metrics  → returns JSON array of latest probe results
 *   POST /api/targets  → adds a new monitoring target (JSON body: {url, interval})
 *   DELETE /api/targets?url=... → removes a monitoring target
 *   POST /api/refresh   → triggers an immediate refresh of all targets
 */
public class DashboardServer {

    private final int port;
    private final MetricsStore metricsStore;
    private final MonitorScheduler scheduler;
    private final String publicDir;
    private HttpServer server;

    public DashboardServer(int port, MetricsStore metricsStore, MonitorScheduler scheduler, String publicDir) {
        this.port = port;
        this.metricsStore = metricsStore;
        this.scheduler = scheduler;
        this.publicDir = publicDir;
    }

    /**
     * Starts the embedded HTTP server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Serve index.html at root
        server.createContext("/", this::handleRoot);

        // REST API endpoints
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/targets", this::handleTargets);
        server.createContext("/api/refresh", this::handleRefresh);

        server.setExecutor(null); // default executor
        server.start();

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   NetPulse Dashboard Server started                     ║");
        System.out.println("║   → http://localhost:" + port + "                              ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /**
     * Serves the index.html file or static content.
     */
    private void handleRoot(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            Path htmlFile = Paths.get(publicDir, "index.html");
            if (Files.exists(htmlFile)) {
                byte[] content = Files.readAllBytes(htmlFile);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else {
                sendError(exchange, 404, "index.html not found. Place it in the public/ directory.");
            }
        } else {
            // Try to serve static files from public/
            Path staticFile = Paths.get(publicDir, path.substring(1));
            if (Files.exists(staticFile) && !Files.isDirectory(staticFile)) {
                byte[] content = Files.readAllBytes(staticFile);
                String contentType = guessContentType(path);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            } else {
                sendError(exchange, 404, "Not Found");
            }
        }
    }

    /**
     * GET /api/metrics → Returns latest probe results as JSON array.
     */
    private void handleMetrics(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        // Build JSON response with metrics + intervals
        StringBuilder sb = new StringBuilder("{\"monitors\":[");
        var results = metricsStore.getAllLatest();
        var it = results.iterator();
        while (it.hasNext()) {
            var r = it.next();
            // Inject interval into the result JSON
            String json = r.toJson();
            // Insert interval before the closing brace
            int interval = scheduler.getInterval(r.getUrl());
            json = json.substring(0, json.length() - 1) + ",\"interval\":" + interval + "}";
            sb.append(json);
            if (it.hasNext()) sb.append(",");
        }
        sb.append("]}");

        sendJson(exchange, 200, sb.toString());
    }

    /**
     * POST /api/targets → Adds a new monitoring target.
     * DELETE /api/targets?url=... → Removes a monitoring target.
     */
    private void handleTargets(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String method = exchange.getRequestMethod().toUpperCase();

        if ("POST".equals(method)) {
            // Read request body
            String body;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }

            // Simple JSON parsing (no external libraries)
            String url = extractJsonString(body, "url");
            int interval = extractJsonInt(body, "interval", 10);

            if (url == null || url.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"Missing 'url' field\"}");
                return;
            }

            // Normalize URL: prepend https:// if no protocol
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            scheduler.addTarget(url, interval);
            sendJson(exchange, 201, "{\"message\":\"Monitor added\",\"url\":\"" + url + "\",\"interval\":" + interval + "}");

        } else if ("DELETE".equals(method)) {
            String query = exchange.getRequestURI().getQuery();
            String url = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "url".equals(kv[0])) {
                        url = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    }
                }
            }

            if (url == null || url.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"Missing 'url' query parameter\"}");
                return;
            }

            boolean removed = scheduler.removeTarget(url);
            if (removed) {
                sendJson(exchange, 200, "{\"message\":\"Monitor removed\",\"url\":\"" + url + "\"}");
            } else {
                sendJson(exchange, 404, "{\"error\":\"Monitor not found\",\"url\":\"" + url + "\"}");
            }

        } else {
            sendError(exchange, 405, "Method Not Allowed");
        }
    }

    /**
     * POST /api/refresh → Triggers an immediate probe of all active targets.
     */
    private void handleRefresh(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        scheduler.refreshAll();
        sendJson(exchange, 200, "{\"message\":\"Refresh triggered for all targets\"}");
    }

    // --- Utility Methods ---

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendJson(exchange, statusCode, "{\"error\":\"" + message + "\"}");
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    /**
     * Simple JSON string field extraction (no library dependency).
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    /**
     * Simple JSON integer field extraction (no library dependency).
     */
    private static int extractJsonInt(String json, String key, int defaultValue) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return defaultValue;
        StringBuilder num = new StringBuilder();
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        try {
            return Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    /**
     * Stops the server.
     */
    public void stop() {
        if (server != null) {
            server.stop(2);
            System.out.println("[DashboardServer] Server stopped.");
        }
    }
}

