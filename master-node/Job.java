import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CAMPUS GRID - JOB ABSTRACTION
 * 
 * Represents a high-level distributed job (e.g. 300-frame 3D animation render, 
 * matrix multiplication, fractal computation) partitioned into discrete executable sub-tasks.
 * Provides thread-safe task progress tracking and flexible frame-range slicing.
 */
public class Job implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String jobName;
    private final String workloadType;
    private final int totalFrames;
    private final long submissionTimestamp;
    private final Map<String, Object> parameters;

    private volatile JobStatus status;
    private final ConcurrentHashMap<String, SubTask> subTasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SubTask> pendingSubTasks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger completedTaskCount = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean postProcessingStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

    public boolean tryStartPostProcessing() {
        return postProcessingStarted.compareAndSet(false, true);
    }

    /**
     * Constructs a new Job.
     *
     * @param jobId Unique identifier for the job.
     * @param jobName Human-readable job name.
     * @param workloadType The workload category ("BLENDER", "MANDELBROT", "MATRIX", etc.).
     * @param totalFrames Total frames or units to process.
     * @param parameters Configuration properties (resolution, scene file, camera settings, etc.).
     */
    public Job(String jobId, String jobName, String workloadType, int totalFrames, Map<String, Object> parameters) {
        this.jobId = jobId;
        this.jobName = jobName;
        this.workloadType = workloadType;
        this.totalFrames = Math.max(1, totalFrames);
        this.status = JobStatus.QUEUED;
        this.submissionTimestamp = System.currentTimeMillis();
        this.parameters = parameters != null ? new ConcurrentHashMap<>(parameters) : new ConcurrentHashMap<>();
    }

    private synchronized byte[] getOrLoadBlendBytes(String blendPath) {
        if (parameters != null && parameters.containsKey("blendFileBytes")) {
            Object obj = parameters.get("blendFileBytes");
            if (obj instanceof byte[] b) return b;
        }
        if (blendPath != null && !blendPath.trim().isEmpty()) {
            File f = new File(blendPath);
            if (f.exists() && f.isFile()) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    if (parameters != null) parameters.put("blendFileBytes", bytes);
                    return bytes;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Slices the job into discrete frame-range sub-tasks.
     *
     * @param framesPerTask Number of frames to allocate per worker task (e.g. 25 frames).
     * @return List of generated SubTask instances.
     */
    public synchronized List<SubTask> sliceIntoFrameRanges(int framesPerTask) {
        subTasks.clear();
        pendingSubTasks.clear();
        completedTaskCount.set(0);

        int chunkSize = Math.max(1, framesPerTask);
        List<SubTask> generated = new ArrayList<>();
        int taskSeq = 1;

        String blendPath = (parameters != null && parameters.containsKey("blendFilePath")) 
            ? parameters.get("blendFilePath").toString() : "scene.blend";
        byte[] blendBytes = getOrLoadBlendBytes(blendPath);

        for (int start = 1; start <= totalFrames; start += chunkSize) {
            int end = Math.min(start + chunkSize - 1, totalFrames);
            String taskId = String.format("%s_T%03d", jobId, taskSeq++);
            String frameRange = (start == end) ? String.valueOf(start) : (start + "-" + end);

            SubTask subTask = new SubTask(taskId, jobId, start, end, frameRange, workloadType);
            subTask.setTaskData(blendBytes != null ? blendBytes : blendPath);
            subTasks.put(taskId, subTask);
            pendingSubTasks.add(subTask);
            generated.add(subTask);
        }

        return generated;
    }

    /**
     * Slices the total frames into custom, spec-weighted frame ranges.
     *
     * @param sliceSizes List of frame counts for each slice.
     * @return Generated list of SubTasks.
     */
    public synchronized List<SubTask> sliceIntoCustomRanges(List<Integer> sliceSizes) {
        subTasks.clear();
        pendingSubTasks.clear();
        List<SubTask> generated = new ArrayList<>();
        int taskSeq = 1;

        String blendPath = (parameters != null && parameters.containsKey("blendFilePath")) 
            ? parameters.get("blendFilePath").toString() : "scene.blend";
        byte[] blendBytes = getOrLoadBlendBytes(blendPath);

        int currentStart = 1;
        for (int i = 0; i < sliceSizes.size(); i++) {
            if (currentStart > totalFrames) break;
            int size = sliceSizes.get(i);
            int currentEnd = (i == sliceSizes.size() - 1) 
                ? totalFrames 
                : Math.min(currentStart + size - 1, totalFrames);

            String taskId = String.format("%s_T%03d", jobId, taskSeq++);
            String frameRange = (currentStart == currentEnd) ? String.valueOf(currentStart) : (currentStart + "-" + currentEnd);

            SubTask subTask = new SubTask(taskId, jobId, currentStart, currentEnd, frameRange, workloadType);
            subTask.setTaskData(blendBytes != null ? blendBytes : blendPath);
            subTasks.put(taskId, subTask);
            pendingSubTasks.add(subTask);
            generated.add(subTask);

            currentStart = currentEnd + 1;
        }

        // Catch-all if totalFrames wasn't fully exhausted
        if (currentStart <= totalFrames) {
            String taskId = String.format("%s_T%03d", jobId, taskSeq++);
            String frameRange = (currentStart == totalFrames) ? String.valueOf(currentStart) : (currentStart + "-" + totalFrames);
            SubTask subTask = new SubTask(taskId, jobId, currentStart, totalFrames, frameRange, workloadType);
            subTask.setTaskData(blendBytes != null ? blendBytes : blendPath);
            subTasks.put(taskId, subTask);
            pendingSubTasks.add(subTask);
            generated.add(subTask);
        }

        return generated;
    }

    /**
     * Retrieves the next pending sub-task to dispatch.
     *
     * @return SubTask if available, null otherwise.
     */
    public SubTask pollPendingSubTask() {
        return pendingSubTasks.poll();
    }

    /**
     * Re-enqueues an unfinished or timed-out sub-task for recovery by another worker.
     *
     * @param subTask The sub-task to re-queue.
     */
    public void requeueSubTask(SubTask subTask) {
        if (subTask != null) {
            subTask.setStatus(SubTaskStatus.PENDING);
            subTask.setAssignedWorkerId(null);
            subTask.incrementRetryCount();
            if (!pendingSubTasks.contains(subTask)) {
                pendingSubTasks.add(subTask);
            }
        }
    }

    private volatile long startTimestamp = 0;
    private volatile long completionTimestamp = 0;

    /**
     * Marks a sub-task completed and checks if the entire job is done.
     *
     * @param taskId Unique identifier of the sub-task.
     * @return true if all sub-tasks in this job are now complete, false otherwise.
     */
    public boolean markSubTaskCompleted(String taskId) {
        SubTask task = subTasks.get(taskId);
        if (task != null && task.getStatus() != SubTaskStatus.COMPLETED) {
            task.setStatus(SubTaskStatus.COMPLETED);
            task.setCompletionTimestamp(System.currentTimeMillis());
            int completed = completedTaskCount.incrementAndGet();
            if (completed >= subTasks.size() && !subTasks.isEmpty()) {
                this.status = JobStatus.COMPLETED;
                if (this.completionTimestamp == 0) {
                    this.completionTimestamp = System.currentTimeMillis();
                }
                return true;
            }
        }
        return isAllCompleted();
    }

    /**
     * Checks if all sub-tasks have finished.
     */
    public boolean isAllCompleted() {
        return !subTasks.isEmpty() && completedTaskCount.get() >= subTasks.size();
    }

    /**
     * Computes the job progress percentage (0.0% to 100.0%).
     */
    public double getProgressPercentage() {
        if (subTasks.isEmpty()) return 0.0;
        return ((double) completedTaskCount.get() / subTasks.size()) * 100.0;
    }

    public String getJobId() { return jobId; }
    public String getJobName() { return jobName; }
    public String getWorkloadType() { return workloadType; }
    public int getTotalFrames() { return totalFrames; }
    public long getSubmissionTimestamp() { return submissionTimestamp; }
    public long getStartTimestamp() { return startTimestamp; }
    public void setStartTimestamp(long t) { this.startTimestamp = t; }
    public long getCompletionTimestamp() { return completionTimestamp; }
    public void setCompletionTimestamp(long t) { this.completionTimestamp = t; }

    public long getDurationMs() {
        if (startTimestamp == 0) {
            if (submissionTimestamp > 0 && status == JobStatus.COMPLETED) {
                return (completionTimestamp > 0 ? completionTimestamp : System.currentTimeMillis()) - submissionTimestamp;
            }
            return 0;
        }
        if (completionTimestamp > 0) {
            return completionTimestamp - startTimestamp;
        }
        return System.currentTimeMillis() - startTimestamp;
    }

    public Map<String, Object> getParameters() { return parameters; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) {
        this.status = status;
        if (status == JobStatus.RUNNING && this.startTimestamp == 0) {
            this.startTimestamp = System.currentTimeMillis();
        } else if ((status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED) && this.completionTimestamp == 0) {
            this.completionTimestamp = System.currentTimeMillis();
        }
    }

    public boolean isWorkStealingEnabled() {
        if (parameters == null) return false;
        Object val = parameters.get("enableWorkStealing");
        if (val instanceof Boolean) return (Boolean) val;
        if (val != null) return Boolean.parseBoolean(val.toString());
        return false; // Disabled by default to prevent cluster clutter
    }

    /**
     * Checks if all frames from 1 to totalFrames exist as valid, non-empty rendered images on disk.
     * Guarantees sequence completeness and allows early completion without waiting for redundant speculative stolen tasks.
     */
    public boolean isAllFramesCovered() {
        if (totalFrames <= 0) return true;
        java.io.File outDir = new java.io.File("./output/" + jobId);
        if (!outDir.exists() || !outDir.isDirectory()) return false;

        java.io.File[] files = outDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
        });
        if (files == null || files.length < totalFrames) return false;

        java.util.BitSet covered = new java.util.BitSet(totalFrames + 1);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?i)(?:frame_?|task_.*_)?(\\d+)");

        for (java.io.File file : files) {
            if (file.isFile() && file.length() > 0) {
                java.util.regex.Matcher m = pattern.matcher(file.getName());
                if (m.find()) {
                    try {
                        int frameNum = Integer.parseInt(m.group(1));
                        if (frameNum >= 1 && frameNum <= totalFrames) {
                            covered.set(frameNum);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return covered.cardinality() >= totalFrames;
    }

    public Collection<SubTask> getSubTasks() {
        return Collections.unmodifiableCollection(subTasks.values());
    }

    public int getSubTaskCount() {
        return subTasks.size();
    }

    public int getCompletedTaskCount() {
        return completedTaskCount.get();
    }

    @Override
    public String toString() {
        return String.format("Job[ID=%s, Name='%s', Type=%s, Frames=%d, Progress=%.1f%%, Status=%s]",
            jobId, jobName, workloadType, totalFrames, getProgressPercentage(), status);
    }

    // ========================================================================
    // NESTED SUB-TASK MODEL
    // ========================================================================

    public enum SubTaskStatus {
        PENDING,
        DISPATCHED,
        COMPLETED,
        FAILED
    }

    public static class SubTask implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String taskId;
        private final String jobId;
        private final int startFrame;
        private final int endFrame;
        private final String frameRange;
        private final String workloadType;

        private volatile SubTaskStatus status;
        private volatile String assignedWorkerId;
        private volatile int retryCount;
        private volatile byte[] taskPayloadBytes;
        private volatile Object taskData;
        private volatile long dispatchTimestamp = 0;
        private volatile long completionTimestamp = 0;

        public SubTask(String taskId, String jobId, int startFrame, int endFrame, String frameRange, String workloadType) {
            this.taskId = taskId;
            this.jobId = jobId;
            this.startFrame = startFrame;
            this.endFrame = endFrame;
            this.frameRange = frameRange;
            this.workloadType = workloadType;
            this.status = SubTaskStatus.PENDING;
            this.retryCount = 0;
        }

        public String getTaskId() { return taskId; }
        public String getJobId() { return jobId; }
        public int getStartFrame() { return startFrame; }
        public int getEndFrame() { return endFrame; }
        public String getFrameRange() { return frameRange; }
        public String getWorkloadType() { return workloadType; }
        
        public SubTaskStatus getStatus() { return status; }
        public void setStatus(SubTaskStatus status) { this.status = status; }

        public String getAssignedWorkerId() { return assignedWorkerId; }
        public void setAssignedWorkerId(String workerId) { this.assignedWorkerId = workerId; }

        public int getRetryCount() { return retryCount; }
        public void incrementRetryCount() { this.retryCount++; }

        public byte[] getTaskPayloadBytes() { 
            if (taskPayloadBytes != null) return taskPayloadBytes;
            if (taskData instanceof byte[] b) return b;
            return null;
        }
        public void setTaskPayloadBytes(byte[] taskPayloadBytes) { this.taskPayloadBytes = taskPayloadBytes; }

        public Object getTaskData() { return taskData; }
        public void setTaskData(Object taskData) { this.taskData = taskData; }

        public long getDispatchTimestamp() { return dispatchTimestamp; }
        public void setDispatchTimestamp(long t) { this.dispatchTimestamp = t; }

        public long getCompletionTimestamp() { return completionTimestamp; }
        public void setCompletionTimestamp(long t) { this.completionTimestamp = t; }

        public long getDurationMs() {
            if (dispatchTimestamp == 0) return 0;
            if (completionTimestamp > 0) return completionTimestamp - dispatchTimestamp;
            return System.currentTimeMillis() - dispatchTimestamp;
        }

        private volatile boolean isStolen = false;
        private volatile String stolenFromWorkerId = null;

        public boolean isStolen() { return isStolen; }
        public void setStolen(boolean stolen) { this.isStolen = stolen; }

        public String getStolenFromWorkerId() { return stolenFromWorkerId; }
        public void setStolenFromWorkerId(String workerId) { this.stolenFromWorkerId = workerId; }

        @Override
        public String toString() {
            return String.format("SubTask[ID=%s, Range=%s, Status=%s, Worker=%s, Retries=%d%s]",
                taskId, frameRange, status, assignedWorkerId != null ? assignedWorkerId : "NONE", retryCount,
                isStolen ? " (Stolen from " + stolenFromWorkerId + ")" : "");
        }
    }

    /**
     * Registers a dynamically stolen sub-task created at runtime for load rebalancing.
     */
    public synchronized void addStolenSubTask(SubTask subTask) {
        if (subTask != null) {
            subTasks.put(subTask.getTaskId(), subTask);
            pendingSubTasks.add(subTask);
        }
    }
}
