package network;

import model.ProbeResult;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * Executes InetAddress DNS lookups and HttpURLConnection health checks
 * against a target URL. Measures DNS resolution time, HTTP status code,
 * and total round-trip response latency.
 */
public class NetworkProber {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * Performs a full network probe against the given URL:
     * 1. DNS resolution via InetAddress.getByName()
     * 2. HTTP GET request via HttpURLConnection
     *
     * @param targetUrl the full URL to probe (e.g. "https://example.com")
     * @return a ProbeResult capturing all measured metrics
     */
    public ProbeResult probe(String targetUrl) {
        String ipAddress = "N/A";
        int dnsTimeMs = -1;
        int statusCode = -1;
        long responseTimeMs = -1;

        try {
            // --- Step 1: DNS Resolution ---
            URL url = new URL(targetUrl);
            String host = url.getHost();

            long dnsStart = System.nanoTime();
            InetAddress address = InetAddress.getByName(host);
            long dnsEnd = System.nanoTime();

            ipAddress = address.getHostAddress();
            dnsTimeMs = (int) ((dnsEnd - dnsStart) / 1_000_000);

            // --- Step 2: HTTP Health Check ---
            long httpStart = System.nanoTime();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NetPulse/1.0 HttpMonitor");

            // Do not cache DNS results at the JVM level
            connection.setUseCaches(false);

            statusCode = connection.getResponseCode();
            long httpEnd = System.nanoTime();

            responseTimeMs = (httpEnd - httpStart) / 1_000_000;

            connection.disconnect();

            return ProbeResult.success(targetUrl, ipAddress, dnsTimeMs, statusCode, responseTimeMs);

        } catch (UnknownHostException e) {
            return ProbeResult.failure(targetUrl, "DNS resolution failed: " + e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            return ProbeResult.failure(targetUrl, "Connection timed out: " + e.getMessage());
        } catch (java.net.ConnectException e) {
            return ProbeResult.failure(targetUrl, "Connection refused: " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            return ProbeResult.failure(targetUrl, "SSL/TLS error: " + e.getMessage());
        } catch (Exception e) {
            return ProbeResult.failure(targetUrl, "Probe error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }
}

