import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * CAMPUS GRID - CLUSTER UTILIZATION TRACKER
 * 
 * An asynchronous background engine that continuously audits and records
 * time-series cluster compute utilization, active node density, and thermal efficiency.
 * 
 * Provides rolling analytics for:
 * 1. Historical Utilization Curve: Busy vs. Idle node ratios across time.
 * 2. Peak Parallel Capacity: Maximum simultaneous active nodes harnessed.
 * 3. Cluster Compute Hours: Accumulated distributed worker-time delivered.
 * 4. JSON Serialization for Web Dashboard REST API (/api/cluster/analytics).
 */
public class ClusterUtilizationTracker implements Runnable {

    private static final int DEFAULT_SAMPLE_INTERVAL_MS = 3000; // 3 seconds
    private static final int MAX_HISTORY_SAMPLES = 600;         // 30 minutes of 3s samples

    public static class UtilizationSample {
        public final long timestamp;
        public final int totalNodes;
        public final int busyNodes;
        public final int idleNodes;
        public final int offlineNodes;
        public final double utilizationPercent;
        public final double avgCpuTemp;
        public final double avgCpuUsage;
        public final double avgRamUsage;

        public UtilizationSample(long timestamp, int totalNodes, int busyNodes, int idleNodes, int offlineNodes,
                                double utilizationPercent, double avgCpuTemp, double avgCpuUsage, double avgRamUsage) {
            this.timestamp = timestamp;
            this.totalNodes = totalNodes;
            this.busyNodes = busyNodes;
            this.idleNodes = idleNodes;
            this.offlineNodes = offlineNodes;
            this.utilizationPercent = utilizationPercent;
            this.avgCpuTemp = avgCpuTemp;
            this.avgCpuUsage = avgCpuUsage;
            this.avgRamUsage = avgRamUsage;
        }

        public String toJson() {
            return String.format(Locale.US,
                "{\"t\":%d,\"total\":%d,\"busy\":%d,\"idle\":%d,\"offline\":%d,\"util\":%.1f,\"temp\":%.1f,\"cpu\":%.1f,\"ram\":%.1f}",
                timestamp, totalNodes, busyNodes, idleNodes, offlineNodes, utilizationPercent, avgCpuTemp, avgCpuUsage, avgRamUsage);
        }
    }

    private final WorkerRegistry workerRegistry;
    private final JobManager jobManager;
    private final int sampleIntervalMs;
    private final List<UtilizationSample> history = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean running = false;
    private Thread trackerThread;
    private long totalBusySecondsAccumulated = 0;

    public ClusterUtilizationTracker(WorkerRegistry workerRegistry, JobManager jobManager) {
        this(workerRegistry, jobManager, DEFAULT_SAMPLE_INTERVAL_MS);
    }

    public ClusterUtilizationTracker(WorkerRegistry workerRegistry, JobManager jobManager, int sampleIntervalMs) {
        this.workerRegistry = workerRegistry;
        this.jobManager = jobManager;
        this.sampleIntervalMs = Math.max(1000, sampleIntervalMs);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        trackerThread = new Thread(this, "ClusterUtilizationTracker-Daemon");
        trackerThread.setDaemon(true);
        trackerThread.start();
        System.out.printf("[ANALYTICS] Cluster Utilization Tracker active (Sample Interval: %ds)%n",
            sampleIntervalMs / 1000);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (trackerThread != null) {
            trackerThread.interrupt();
            trackerThread = null;
        }
        System.out.println("[ANALYTICS] Cluster Utilization Tracker stopped.");
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                recordSample();
                Thread.sleep(sampleIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[ANALYTICS-ERR] Error recording utilization sample: " + e.getMessage());
            }
        }
    }

    /**
     * Records a single telemetry snapshot of current cluster utilization.
     */
    public void recordSample() {
        var allWorkers = workerRegistry.getAllWorkers();
        int total = 0;
        int busy = 0;
        int idle = 0;
        int offline = 0;
        double sumTemp = 0;
        double sumCpu = 0;
        double sumRam = 0;
        int onlineCount = 0;

        for (WorkerState w : allWorkers) {
            total++;
            WorkerStatus status = w.getStatus();
            if (status == WorkerStatus.BUSY) {
                busy++;
                onlineCount++;
                sumTemp += w.getCpuTemperature();
                sumCpu += w.getCpuUsagePercent();
                sumRam += w.getRamUsagePercent();
            } else if (status == WorkerStatus.IDLE) {
                idle++;
                onlineCount++;
                sumTemp += w.getCpuTemperature();
                sumCpu += w.getCpuUsagePercent();
                sumRam += w.getRamUsagePercent();
            } else {
                offline++;
            }
        }

        double utilPct = (onlineCount > 0) ? ((double) busy / onlineCount * 100.0) : 0.0;
        double avgTemp = (onlineCount > 0) ? (sumTemp / onlineCount) : 0.0;
        double avgCpu = (onlineCount > 0) ? (sumCpu / onlineCount) : 0.0;
        double avgRam = (onlineCount > 0) ? (sumRam / onlineCount) : 0.0;

        if (busy > 0) {
            totalBusySecondsAccumulated += (long) (busy * (sampleIntervalMs / 1000.0));
        }

        UtilizationSample sample = new UtilizationSample(
            System.currentTimeMillis(), total, busy, idle, offline, utilPct, avgTemp, avgCpu, avgRam
        );

        synchronized (history) {
            history.add(sample);
            if (history.size() > MAX_HISTORY_SAMPLES) {
                history.remove(0); // Evict oldest sample to maintain ring buffer bounds
            }
        }
    }

    /**
     * Computes the average utilization across recorded history (0.0% to 100.0%).
     */
    public double getAverageUtilization() {
        synchronized (history) {
            if (history.isEmpty()) return 0.0;
            double sum = 0.0;
            for (UtilizationSample s : history) {
                sum += s.utilizationPercent;
            }
            return sum / history.size();
        }
    }

    /**
     * Retrieves the peak number of simultaneously busy parallel workers.
     */
    public int getPeakBusyNodes() {
        synchronized (history) {
            int peak = 0;
            for (UtilizationSample s : history) {
                if (s.busyNodes > peak) peak = s.busyNodes;
            }
            return peak;
        }
    }

    /**
     * Serializes recent analytics into JSON for the REST API.
     */
    public String generateAnalyticsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        sb.append("\"avgUtilization\":").append(String.format(Locale.US, "%.1f", getAverageUtilization())).append(",");
        sb.append("\"peakBusyNodes\":").append(getPeakBusyNodes()).append(",");
        sb.append("\"totalComputeHours\":").append(String.format(Locale.US, "%.3f", totalBusySecondsAccumulated / 3600.0)).append(",");
        sb.append("\"sampleCount\":").append(history.size()).append(",");

        sb.append("\"samples\":[");
        synchronized (history) {
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(history.get(i).toJson());
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
