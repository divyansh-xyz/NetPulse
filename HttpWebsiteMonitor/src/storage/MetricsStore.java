package storage;

import model.ProbeResult;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages in-memory latest probe results, a ring buffer of historical data per URL,
 * and persistent CSV + failure log writers to the data/ directory.
 */
public class MetricsStore {

    /** Maximum number of historical probe results to keep per URL (ring buffer). */
    private static final int RING_BUFFER_SIZE = 100;

    /** Latest probe result per monitored URL. */
    private final Map<String, ProbeResult> latestResults;

    /** Ring buffer of historical results per URL. */
    private final Map<String, LinkedList<ProbeResult>> history;

    /** Path to the CSV metrics file. */
    private final Path csvPath;

    /** Path to the failure log file. */
    private final Path failureLogPath;

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public MetricsStore(String dataDir) {
        this.latestResults = new ConcurrentHashMap<>();
        this.history = new ConcurrentHashMap<>();
        this.csvPath = Paths.get(dataDir, "monitoring_metrics.csv");
        this.failureLogPath = Paths.get(dataDir, "failures.log");

        // Ensure the data directory exists
        try {
            Files.createDirectories(Paths.get(dataDir));
            initCsvFile();
        } catch (IOException e) {
            System.err.println("[MetricsStore] Failed to initialize data directory: " + e.getMessage());
        }
    }

    /**
     * Creates the CSV file with a header row if it does not already exist.
     */
    private void initCsvFile() throws IOException {
        if (!Files.exists(csvPath)) {
            try (BufferedWriter writer = Files.newBufferedWriter(csvPath, StandardOpenOption.CREATE)) {
                writer.write("timestamp,url,ip,status,dns_ms,http_status,response_ms,error");
                writer.newLine();
            }
        }
    }

    /**
     * Records a probe result: updates the latest state, appends to the ring buffer,
     * writes to CSV, and logs failures.
     *
     * @param url    the monitored URL
     * @param result the probe result to store
     */
    public synchronized void record(String url, ProbeResult result) {
        // Update latest result
        latestResults.put(url, result);

        // Append to ring buffer
        history.computeIfAbsent(url, k -> new LinkedList<>());
        LinkedList<ProbeResult> ring = history.get(url);
        ring.addLast(result);
        if (ring.size() > RING_BUFFER_SIZE) {
            ring.removeFirst();
        }

        // Persist to CSV
        appendToCsv(result);

        // Log failures
        if (!result.isUp()) {
            logFailure(result);
        }
    }

    /**
     * Appends a probe result row to the CSV metrics file.
     */
    private void appendToCsv(ProbeResult result) {
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath,
                StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            writer.write(result.toCsvRow());
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[MetricsStore] CSV write error: " + e.getMessage());
        }
    }

    /**
     * Appends a failure entry to the failures.log file.
     */
    private void logFailure(ProbeResult result) {
        try (BufferedWriter writer = Files.newBufferedWriter(failureLogPath,
                StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            String timestamp = TIMESTAMP_FMT.format(Instant.ofEpochMilli(result.getTimestamp()));
            writer.write(String.format("[%s] FAILURE | URL: %s | HTTP: %d | Error: %s",
                    timestamp,
                    result.getUrl(),
                    result.getStatusCode(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "HTTP " + result.getStatusCode()
            ));
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[MetricsStore] Failure log write error: " + e.getMessage());
        }
    }

    /**
     * Returns the latest probe result for a given URL, or null if never probed.
     */
    public ProbeResult getLatest(String url) {
        return latestResults.get(url);
    }

    /**
     * Returns all latest probe results as a collection.
     */
    public Collection<ProbeResult> getAllLatest() {
        return latestResults.values();
    }

    /**
     * Returns the historical ring buffer for a URL.
     */
    public List<ProbeResult> getHistory(String url) {
        return history.getOrDefault(url, new LinkedList<>());
    }

    /**
     * Removes all stored data for a URL.
     */
    public void remove(String url) {
        latestResults.remove(url);
        history.remove(url);
    }

    /**
     * Returns the number of currently tracked URLs.
     */
    public int size() {
        return latestResults.size();
    }

    /**
     * Builds the full JSON array for the /api/metrics endpoint.
     */
    public String toJsonArray() {
        StringBuilder sb = new StringBuilder("[");
        Iterator<ProbeResult> it = latestResults.values().iterator();
        while (it.hasNext()) {
            sb.append(it.next().toJson());
            if (it.hasNext()) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}

