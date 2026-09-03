import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CAMPUS GRID - WORKER RELIABILITY TRACKER
 * 
 * Evaluates the historical operational performance and fault stability of cluster worker nodes.
 * Dynamically computes a normalized Reliability Score (0.0 to 1.0) based on:
 * 1. Task Completion Success Rate (Completed Tasks vs. Failed/Dropped Tasks)
 * 2. Socket Disconnect and Watchdog Timeout Frequency
 * 3. Execution Stability (variance from cluster average duration)
 * 
 * Schedulers use this score to prioritize reliable nodes for large render slices
 * and avoid repeatedly assigning work to unstable, throttling, or crashing nodes.
 */
public class WorkerReliabilityTracker {

    public static class WorkerMetrics {
        public final String workerId;
        private final AtomicInteger tasksCompleted = new AtomicInteger(0);
        private final AtomicInteger tasksFailed = new AtomicInteger(0);
        private final AtomicInteger disconnectCount = new AtomicInteger(0);
        private final AtomicLong totalDurationMs = new AtomicLong(0);
        private volatile long lastEventTimestamp = System.currentTimeMillis();

        public WorkerMetrics(String workerId) {
            this.workerId = workerId;
        }

        public int getTasksCompleted() { return tasksCompleted.get(); }
        public int getTasksFailed() { return tasksFailed.get(); }
        public int getDisconnectCount() { return disconnectCount.get(); }
        public long getTotalTasks() { return (long) tasksCompleted.get() + tasksFailed.get(); }

        public double getAvgDurationMs() {
            int completed = tasksCompleted.get();
            return (completed > 0) ? ((double) totalDurationMs.get() / completed) : 0.0;
        }

        /**
         * Calculates normalized reliability score between 0.1 and 1.0.
         * New nodes start with a 1.0 default rating.
         */
        public double getReliabilityScore() {
            int completed = tasksCompleted.get();
            int failed = tasksFailed.get();
            int disconnects = disconnectCount.get();
            int total = completed + failed;

            if (total == 0 && disconnects == 0) {
                return 1.0; // Clean slate for newly connected workers
            }

            double successRate = (total > 0) ? ((double) completed / total) : 0.8;
            double disconnectPenalty = Math.min(0.4, disconnects * 0.08);

            double score = successRate - disconnectPenalty;
            return Math.max(0.1, Math.min(1.0, score));
        }

        public String getFormattedScore() {
            return String.format(Locale.US, "%.0f%%", getReliabilityScore() * 100.0);
        }

        public String toJson() {
            return String.format(Locale.US,
                "{\"workerId\":\"%s\",\"tasksCompleted\":%d,\"tasksFailed\":%d,\"disconnectCount\":%d," +
                "\"avgDurationMs\":%.1f,\"reliabilityScore\":%.2f,\"reliabilityFormatted\":\"%s\"}",
                workerId, tasksCompleted.get(), tasksFailed.get(), disconnectCount.get(),
                getAvgDurationMs(), getReliabilityScore(), getFormattedScore());
        }
    }

    private final ConcurrentHashMap<String, WorkerMetrics> metricsMap = new ConcurrentHashMap<>();

    private WorkerMetrics getOrCreate(String workerId) {
        if (workerId == null) return new WorkerMetrics("UNKNOWN");
        return metricsMap.computeIfAbsent(workerId, WorkerMetrics::new);
    }

    /**
     * Records a successful sub-task execution by a worker.
     */
    public void recordTaskSuccess(String workerId, long durationMs) {
        WorkerMetrics m = getOrCreate(workerId);
        m.tasksCompleted.incrementAndGet();
        if (durationMs > 0) {
            m.totalDurationMs.addAndGet(durationMs);
        }
        m.lastEventTimestamp = System.currentTimeMillis();
    }

    /**
     * Records a failed sub-task execution (e.g. timeout, process crash, corrupted output).
     */
    public void recordTaskFailure(String workerId, String reason) {
        WorkerMetrics m = getOrCreate(workerId);
        m.tasksFailed.incrementAndGet();
        m.lastEventTimestamp = System.currentTimeMillis();
        System.out.printf("[RELIABILITY-WARN] Recorded failure for Worker [%s] (Reason: %s, Current Score: %s)%n",
            workerId, reason, m.getFormattedScore());
    }

    /**
     * Records an unexpected socket disconnection or heartbeat timeout.
     */
    public void recordWorkerDisconnect(String workerId) {
        WorkerMetrics m = getOrCreate(workerId);
        m.disconnectCount.incrementAndGet();
        m.lastEventTimestamp = System.currentTimeMillis();
        System.out.printf("[RELIABILITY-WARN] Recorded disconnect for Worker [%s] (Total Disconnects: %d, Score: %s)%n",
            workerId, m.disconnectCount.get(), m.getFormattedScore());
    }

    /**
     * Retrieves the composite reliability score for a given worker (0.1 to 1.0).
     */
    public double getReliabilityScore(String workerId) {
        if (workerId == null) return 1.0;
        WorkerMetrics m = metricsMap.get(workerId);
        return (m != null) ? m.getReliabilityScore() : 1.0;
    }

    /**
     * Retrieves the metrics object for a worker.
     */
    public WorkerMetrics getMetrics(String workerId) {
        return getOrCreate(workerId);
    }

    /**
     * Returns a snapshot of all worker metrics.
     */
    public Map<String, WorkerMetrics> getAllMetrics() {
        return metricsMap;
    }
}
