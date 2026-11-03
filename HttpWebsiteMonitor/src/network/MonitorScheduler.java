package network;

import model.ProbeResult;
import storage.MetricsStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages a ScheduledExecutorService for concurrent website monitoring targets.
 * Each target URL runs on its own periodic schedule defined by a configurable interval.
 */
public class MonitorScheduler {

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> activeTasks;
    private final Map<String, Integer> intervals; // URL -> interval in seconds
    private final NetworkProber prober;
    private final MetricsStore metricsStore;

    public MonitorScheduler(MetricsStore metricsStore) {
        this.scheduler = Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors(),
                r -> {
                    Thread t = new Thread(r, "NetPulse-Probe");
                    t.setDaemon(true);
                    return t;
                }
        );
        this.activeTasks = new ConcurrentHashMap<>();
        this.intervals = new ConcurrentHashMap<>();
        this.prober = new NetworkProber();
        this.metricsStore = metricsStore;
    }

    /**
     * Adds a URL to the monitoring schedule. If already monitored, it is removed and re-added.
     *
     * @param url            the target URL to monitor
     * @param intervalSeconds the polling interval in seconds
     */
    public void addTarget(String url, int intervalSeconds) {
        // Remove existing schedule if present
        removeTarget(url);

        intervals.put(url, intervalSeconds);

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                ProbeResult result = prober.probe(url);
                metricsStore.record(url, result);
                System.out.println("[Probe] " + result);
            } catch (Exception e) {
                System.err.println("[Probe Error] " + url + " -> " + e.getMessage());
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);

        activeTasks.put(url, future);
        System.out.println("[Scheduler] Added target: " + url + " (every " + intervalSeconds + "s)");
    }

    /**
     * Removes a URL from the monitoring schedule.
     *
     * @param url the target URL to stop monitoring
     * @return true if the target was found and removed
     */
    public boolean removeTarget(String url) {
        ScheduledFuture<?> existing = activeTasks.remove(url);
        intervals.remove(url);
        if (existing != null) {
            existing.cancel(false);
            metricsStore.remove(url);
            System.out.println("[Scheduler] Removed target: " + url);
            return true;
        }
        return false;
    }

    /**
     * Returns the list of currently monitored URLs.
     */
    public List<String> getActiveTargets() {
        return List.copyOf(activeTasks.keySet());
    }

    /**
     * Returns the configured polling interval for a target URL.
     */
    public int getInterval(String url) {
        return intervals.getOrDefault(url, 10);
    }

    /**
     * Triggers an immediate probe of all active targets (manual refresh).
     */
    public void refreshAll() {
        for (String url : activeTasks.keySet()) {
            scheduler.execute(() -> {
                try {
                    ProbeResult result = prober.probe(url);
                    metricsStore.record(url, result);
                    System.out.println("[Refresh] " + result);
                } catch (Exception e) {
                    System.err.println("[Refresh Error] " + url + " -> " + e.getMessage());
                }
            });
        }
    }

    /**
     * Gracefully shuts down the scheduler, waiting up to 5 seconds for active tasks.
     */
    public void shutdown() {
        System.out.println("[Scheduler] Shutting down...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

