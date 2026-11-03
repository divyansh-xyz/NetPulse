package model;

/**
 * Data model representing the result of a single HTTP probe against a monitored target.
 * Captures availability status, DNS resolution time, HTTP status code, and total response latency.
 */
public class ProbeResult {

    private final String url;
    private final String ipAddress;
    private final String status;        // "UP" or "DOWN"
    private final int dnsTimeMs;        // DNS lookup time in milliseconds
    private final int statusCode;       // HTTP response status code (e.g. 200, 503, -1 for failures)
    private final long responseTimeMs;  // Total round-trip response time in milliseconds
    private final long timestamp;       // Unix epoch millis when this probe was executed
    private final String errorMessage;  // Null if no error; descriptive message otherwise

    public ProbeResult(String url, String ipAddress, String status, int dnsTimeMs,
                       int statusCode, long responseTimeMs, long timestamp, String errorMessage) {
        this.url = url;
        this.ipAddress = ipAddress;
        this.status = status;
        this.dnsTimeMs = dnsTimeMs;
        this.statusCode = statusCode;
        this.responseTimeMs = responseTimeMs;
        this.timestamp = timestamp;
        this.errorMessage = errorMessage;
    }

    // --- Static factory methods ---

    /**
     * Creates a successful probe result.
     */
    public static ProbeResult success(String url, String ipAddress, int dnsTimeMs,
                                      int statusCode, long responseTimeMs) {
        String status = (statusCode >= 200 && statusCode < 400) ? "UP" : "DOWN";
        return new ProbeResult(url, ipAddress, status, dnsTimeMs, statusCode,
                responseTimeMs, System.currentTimeMillis(), null);
    }

    /**
     * Creates a failed probe result (DNS failure, timeout, connection refused, etc.).
     */
    public static ProbeResult failure(String url, String errorMessage) {
        return new ProbeResult(url, "N/A", "DOWN", -1, -1, -1,
                System.currentTimeMillis(), errorMessage);
    }

    // --- Getters ---

    public String getUrl() {
        return url;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getStatus() {
        return status;
    }

    public int getDnsTimeMs() {
        return dnsTimeMs;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isUp() {
        return "UP".equals(status);
    }

    /**
     * Returns a JSON-compatible string representation for the REST API.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"url\":\"").append(escapeJson(url)).append("\",");
        sb.append("\"ip\":\"").append(escapeJson(ipAddress)).append("\",");
        sb.append("\"status\":\"").append(status).append("\",");
        sb.append("\"dnsTime\":").append(dnsTimeMs).append(",");
        sb.append("\"statusCode\":").append(statusCode).append(",");
        sb.append("\"responseTime\":").append(responseTimeMs).append(",");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        sb.append("\"lastChecked\":\"").append(getRelativeTime()).append("\"");
        if (errorMessage != null) {
            sb.append(",\"error\":\"").append(escapeJson(errorMessage)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Returns a CSV row for persistent metrics logging.
     */
    public String toCsvRow() {
        return String.join(",",
                String.valueOf(timestamp),
                escapeJson(url),
                ipAddress,
                status,
                String.valueOf(dnsTimeMs),
                String.valueOf(statusCode),
                String.valueOf(responseTimeMs),
                errorMessage != null ? escapeJson(errorMessage) : ""
        );
    }

    /**
     * Returns a human-readable relative time string (e.g. "Just now", "5s ago").
     */
    private String getRelativeTime() {
        long elapsed = System.currentTimeMillis() - timestamp;
        if (elapsed < 2000) return "Just now";
        if (elapsed < 60000) return (elapsed / 1000) + "s ago";
        if (elapsed < 3600000) return (elapsed / 60000) + "m ago";
        return (elapsed / 3600000) + "h ago";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public String toString() {
        return String.format("[%s] %s | IP: %s | DNS: %dms | HTTP %d | Latency: %dms",
                status, url, ipAddress, dnsTimeMs, statusCode, responseTimeMs);
    }
}

