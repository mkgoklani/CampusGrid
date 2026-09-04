import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CAMPUS GRID - RENDER ETA ESTIMATOR
 * 
 * Provides real-time Estimated Time of Arrival (ETA) calculations for active
 * distributed render jobs by tracking per-frame completion durations and computing
 * a rolling weighted average across the cluster.
 * 
 * ETA Calculation Strategy:
 * 1. Records individual frame render durations as they complete.
 * 2. Computes an exponentially weighted moving average (EWMA) for smoothness.
 * 3. Estimates remaining time: avgFrameTime × remainingFrames ÷ activeWorkerCount.
 * 4. Applies a straggler penalty factor based on frame time variance.
 * 
 * Thread-safe: All methods are safe to call from multiple threads concurrently.
 */
public class RenderETAEstimator {

    /** Per-job estimation state */
    private static class JobEstimate {
        final String jobId;
        final List<Long> frameDurations = Collections.synchronizedList(new ArrayList<>());
        volatile double ewmaMs = 0.0;         // Exponentially Weighted Moving Average
        volatile long lastUpdateTimestamp = 0;
        volatile int totalFrames = 0;
        volatile int completedFrames = 0;
        volatile int activeWorkers = 0;

        // EWMA smoothing factor (0.3 = responsive to recent changes, 0.1 = more stable)
        static final double ALPHA = 0.3;

        JobEstimate(String jobId, int totalFrames) {
            this.jobId = jobId;
            this.totalFrames = totalFrames;
        }
    }

    private final ConcurrentHashMap<String, JobEstimate> estimates = new ConcurrentHashMap<>();

    /**
     * Initializes tracking for a new render job.
     * 
     * @param jobId The unique job identifier.
     * @param totalFrames Total frames in the render job.
     */
    public void initJob(String jobId, int totalFrames) {
        estimates.put(jobId, new JobEstimate(jobId, totalFrames));
    }

    /**
     * Records a completed frame render duration for ETA calculation.
     * Called by ResultCollector when a sub-task completes successfully.
     * 
     * @param jobId The job this frame belongs to.
     * @param frameCount Number of frames completed in this sub-task.
     * @param durationMs Total render duration for the sub-task in milliseconds.
     */
    public void recordFrameCompletion(String jobId, int frameCount, long durationMs) {
        JobEstimate est = estimates.get(jobId);
        if (est == null) return;

        // Calculate per-frame duration
        long perFrameMs = (frameCount > 0) ? (durationMs / frameCount) : durationMs;
        if (perFrameMs <= 0) perFrameMs = 1; // Guard against zero/negative

        est.frameDurations.add(perFrameMs);
        est.completedFrames += frameCount;
        est.lastUpdateTimestamp = System.currentTimeMillis();

        // Update EWMA: smooth estimate that adapts to changing render complexity
        if (est.ewmaMs <= 0.0) {
            est.ewmaMs = perFrameMs; // First sample: initialize directly
        } else {
            est.ewmaMs = (JobEstimate.ALPHA * perFrameMs) + ((1.0 - JobEstimate.ALPHA) * est.ewmaMs);
        }
    }

    /**
     * Updates the count of active (BUSY) workers for a job.
     * Used to estimate parallelism for ETA division.
     * 
     * @param jobId The job identifier.
     * @param activeWorkerCount Number of workers currently rendering frames for this job.
     */
    public void updateActiveWorkers(String jobId, int activeWorkerCount) {
        JobEstimate est = estimates.get(jobId);
        if (est != null) {
            est.activeWorkers = Math.max(1, activeWorkerCount);
        }
    }

    /**
     * Computes the current ETA snapshot for a specific job.
     * 
     * @param jobId The job identifier.
     * @return ETASnapshot with remaining time estimate, or null if no data available.
     */
    public ETASnapshot getETA(String jobId) {
        JobEstimate est = estimates.get(jobId);
        if (est == null || est.frameDurations.isEmpty()) {
            return null;
        }

        int remainingFrames = est.totalFrames - est.completedFrames;
        if (remainingFrames <= 0) {
            return new ETASnapshot(jobId, 0, 0, est.completedFrames, est.totalFrames,
                est.ewmaMs, 0.0, est.activeWorkers, 100.0);
        }

        int workers = Math.max(1, est.activeWorkers);

        // Base ETA: avgFrameTime × remaining ÷ parallel workers
        double baseEtaMs = est.ewmaMs * remainingFrames / workers;

        // Straggler penalty: compute coefficient of variation of frame durations
        double varianceFactor = computeVarianceFactor(est);

        // Apply penalty (10-30% increase based on variance)
        double adjustedEtaMs = baseEtaMs * (1.0 + varianceFactor * 0.15);

        double progressPct = (est.totalFrames > 0)
            ? ((double) est.completedFrames / est.totalFrames * 100.0)
            : 0.0;

        return new ETASnapshot(
            jobId,
            (long) adjustedEtaMs,
            System.currentTimeMillis() + (long) adjustedEtaMs,
            est.completedFrames,
            est.totalFrames,
            est.ewmaMs,
            varianceFactor,
            workers,
            progressPct
        );
    }

    /**
     * Removes tracking data for a completed or cancelled job.
     */
    public void removeJob(String jobId) {
        estimates.remove(jobId);
    }

    /**
     * Computes the Coefficient of Variation (CV = stddev / mean) of frame durations.
     * Higher CV indicates more variance in render times (complex scenes, stragglers).
     * Returns 0.0 (no penalty) to ~2.0 (high penalty).
     */
    private double computeVarianceFactor(JobEstimate est) {
        Long[] snapshot;
        synchronized (est.frameDurations) {
            if (est.frameDurations.size() < 2) return 0.0;
            int len = est.frameDurations.size();
            int startIdx = Math.max(0, len - 20);
            snapshot = est.frameDurations.subList(startIdx, len).toArray(new Long[0]);
        }
        if (snapshot.length < 2) return 0.0;

        double sum = 0.0;
        for (Long d : snapshot) {
            sum += (d != null ? d : 0);
        }
        double mean = sum / snapshot.length;

        double varianceSum = 0.0;
        for (Long d : snapshot) {
            double diff = (d != null ? d : 0) - mean;
            varianceSum += diff * diff;
        }
        double stddev = Math.sqrt(varianceSum / snapshot.length);

        return (mean > 0) ? (stddev / mean) : 0.0;
    }

    // ========================================================================
    // ETA SNAPSHOT DATA CLASS
    // ========================================================================

    /**
     * Immutable snapshot of ETA estimation for a render job at a point in time.
     */
    public static class ETASnapshot {
        public final String jobId;
        public final long remainingMs;            // Estimated milliseconds to completion
        public final long estimatedCompletionTime; // Unix timestamp of estimated finish
        public final int completedFrames;
        public final int totalFrames;
        public final double avgFrameTimeMs;        // EWMA per-frame render time
        public final double varianceFactor;        // Coefficient of variation (straggler indicator)
        public final int activeWorkers;
        public final double progressPercentage;

        public ETASnapshot(String jobId, long remainingMs, long estimatedCompletionTime,
                          int completedFrames, int totalFrames, double avgFrameTimeMs,
                          double varianceFactor, int activeWorkers, double progressPercentage) {
            this.jobId = jobId;
            this.remainingMs = remainingMs;
            this.estimatedCompletionTime = estimatedCompletionTime;
            this.completedFrames = completedFrames;
            this.totalFrames = totalFrames;
            this.avgFrameTimeMs = avgFrameTimeMs;
            this.varianceFactor = varianceFactor;
            this.activeWorkers = activeWorkers;
            this.progressPercentage = progressPercentage;
        }

        /**
         * Formats the remaining time as a human-readable string (e.g., "2m 35s", "45s").
         */
        public String formatRemaining() {
            if (remainingMs <= 0) return "Complete";
            long totalSec = remainingMs / 1000;
            long hours = totalSec / 3600;
            long minutes = (totalSec % 3600) / 60;
            long seconds = totalSec % 60;
            if (hours > 0) {
                return String.format("%dh %02dm %02ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm %02ds", minutes, seconds);
            } else {
                return String.format("%ds", seconds);
            }
        }

        /**
         * Serializes this snapshot to a JSON string for REST API response.
         */
        public String toJson() {
            return String.format(Locale.US,
                "{\"remainingMs\":%d,\"estimatedCompletionTime\":%d," +
                "\"remainingFormatted\":\"%s\",\"completedFrames\":%d,\"totalFrames\":%d," +
                "\"avgFrameTimeMs\":%.1f,\"varianceFactor\":%.3f,\"activeWorkers\":%d," +
                "\"progressPercentage\":%.1f}",
                remainingMs, estimatedCompletionTime,
                formatRemaining(), completedFrames, totalFrames,
                avgFrameTimeMs, varianceFactor, activeWorkers,
                progressPercentage);
        }
    }
}
