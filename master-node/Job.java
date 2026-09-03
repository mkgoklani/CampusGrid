import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import com.campusgrid.core.*;


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
    private volatile long completedTimestamp = 0;
    private volatile String compiledVideoUrl = null;
    private final ConcurrentHashMap<String, SubTask> subTasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SubTask> pendingSubTasks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger completedTaskCount = new AtomicInteger(0);

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
        Object blendBytes = (parameters != null) ? parameters.get("blendFileBytes") : null;

        String renderEngine = (parameters != null && parameters.containsKey("renderEngine"))
            ? parameters.get("renderEngine").toString() : "CYCLES";

        for (int start = 1; start <= totalFrames; start += chunkSize) {
            int end = Math.min(start + chunkSize - 1, totalFrames);
            String taskId = String.format("%s_T%03d", jobId, taskSeq++);
            String frameRange = (start == end) ? String.valueOf(start) : (start + "-" + end);

            SubTask subTask = new SubTask(taskId, jobId, start, end, frameRange, workloadType, renderEngine);
            subTask.setTaskData(blendBytes != null ? blendBytes : blendPath);
            if (blendBytes instanceof byte[] b) {
                subTask.setTaskPayloadBytes(b);
            }
            subTasks.put(taskId, subTask);
            pendingSubTasks.add(subTask);
            generated.add(subTask);
        }

        return generated;
    }

    /**
     * Slices the total frames into custom, spec-weighted frame ranges based on hardware capabilities.
     *
     * @param sliceSizes List of frame counts for each slice.
     * @return Generated list of SubTasks.
     */
    public synchronized List<SubTask> sliceIntoCustomRanges(List<Integer> sliceSizes) {
        subTasks.clear();
        pendingSubTasks.clear();
        completedTaskCount.set(0);
        List<SubTask> generated = new ArrayList<>();
        int taskSeq = 1;

        String blendPath = (parameters != null && parameters.containsKey("blendFilePath")) 
            ? parameters.get("blendFilePath").toString() : "scene.blend";
        Object blendBytes = (parameters != null) ? parameters.get("blendFileBytes") : null;

        String renderEngine = (parameters != null && parameters.containsKey("renderEngine"))
            ? parameters.get("renderEngine").toString() : "CYCLES";

        int currentStart = 1;
        for (int i = 0; i < sliceSizes.size(); i++) {
            if (currentStart > totalFrames) break;
            int size = sliceSizes.get(i);
            int currentEnd = (i == sliceSizes.size() - 1) 
                ? totalFrames 
                : Math.min(currentStart + size - 1, totalFrames);

            String taskId = String.format("%s_T%03d", jobId, taskSeq++);
            String frameRange = (currentStart == currentEnd) ? String.valueOf(currentStart) : (currentStart + "-" + currentEnd);

            SubTask subTask = new SubTask(taskId, jobId, currentStart, currentEnd, frameRange, workloadType, renderEngine);
            subTask.setTaskData(blendBytes != null ? blendBytes : blendPath);
            if (blendBytes instanceof byte[] b) {
                subTask.setTaskPayloadBytes(b);
            }
            subTasks.put(taskId, subTask);
            pendingSubTasks.add(subTask);
            generated.add(subTask);

            currentStart = currentEnd + 1;
        }

        if (currentStart <= totalFrames) {
            String taskId = String.format("%s_T%03d", jobId, taskSeq++);
            String frameRange = (currentStart == totalFrames) ? String.valueOf(currentStart) : (currentStart + "-" + totalFrames);
            SubTask subTask = new SubTask(taskId, jobId, currentStart, totalFrames, frameRange, workloadType, renderEngine);
            subTask.setTaskData(blendBytes != null ? blendBytes : blendPath);
            if (blendBytes instanceof byte[] b) {
                subTask.setTaskPayloadBytes(b);
            }
            subTasks.put(taskId, subTask);
            pendingSubTasks.add(subTask);
            generated.add(subTask);
        }

        return generated;
    }

    /**
     * Retrieves the next pending sub-task to dispatch.
     * Skips any tasks that are already COMPLETED or DISPATCHED.
     *
     * @return SubTask if available, null otherwise.
     */
    public synchronized SubTask pollPendingSubTask() {
        while (!pendingSubTasks.isEmpty()) {
            SubTask task = pendingSubTasks.poll();
            if (task != null && task.getStatus() == SubTaskStatus.PENDING) {
                task.setStatus(SubTaskStatus.DISPATCHED);
                return task;
            }
        }
        return null;
    }

    /**
     * Retrieves the best hardware-matched pending sub-task for a given worker.
     * High-spec workers (GPU score >= 2.5) are allocated the largest available slice,
     * while lower-spec workers are allocated smaller slices to minimize execution latency.
     *
     * @param workerScore The ComputeCapabilityEngine score of the target worker.
     * @return Best matched SubTask if available, null otherwise.
     */
    public synchronized SubTask pollBestSubTaskForWorker(double workerScore) {
        if (pendingSubTasks.isEmpty()) return null;
        if (pendingSubTasks.size() == 1) return pollPendingSubTask();

        boolean wantLargest = (workerScore >= 2.5);
        SubTask chosen = null;
        int targetSize = wantLargest ? -1 : Integer.MAX_VALUE;

        for (SubTask st : pendingSubTasks) {
            if (st.getStatus() != SubTaskStatus.PENDING) continue;
            int size = st.getEndFrame() - st.getStartFrame() + 1;
            if (wantLargest) {
                if (size > targetSize) {
                    targetSize = size;
                    chosen = st;
                }
            } else {
                if (size < targetSize) {
                    targetSize = size;
                    chosen = st;
                }
            }
        }

        if (chosen != null) {
            pendingSubTasks.remove(chosen);
            chosen.setStatus(SubTaskStatus.DISPATCHED);
            return chosen;
        }

        return pollPendingSubTask();
    }

    /**
     * Checks if all frame numbers from 1 to totalFrames are covered by completed tasks.
     */
    public boolean isAllFramesCovered() {
        if (totalFrames <= 0) return true;
        boolean[] covered = new boolean[totalFrames + 1];
        int count = 0;
        for (SubTask st : subTasks.values()) {
            if (st.getStatus() == SubTaskStatus.COMPLETED) {
                for (int f = Math.max(1, st.getStartFrame()); f <= Math.min(totalFrames, st.getEndFrame()); f++) {
                    if (!covered[f]) {
                        covered[f] = true;
                        count++;
                    }
                }
            }
        }
        return count >= totalFrames;
    }

    /**
     * Re-enqueues an unfinished or timed-out sub-task for recovery by another worker.
     * Guarded against already completed tasks and duplicate queueing.
     *
     * @param subTask The sub-task to re-queue.
     */
    public synchronized void requeueSubTask(SubTask subTask) {
        if (subTask != null && subTask.getStatus() != SubTaskStatus.COMPLETED) {
            subTask.setStatus(SubTaskStatus.PENDING);
            subTask.setAssignedWorkerId(null);
            subTask.incrementRetryCount();
            if (!pendingSubTasks.contains(subTask)) {
                pendingSubTasks.add(subTask);
            }
        }
    }

    /**
     * Marks a sub-task completed and checks if the entire job is done.
     *
     * @param taskId Unique identifier of the sub-task.
     * @return true if all sub-tasks or all frames in this job are now complete, false otherwise.
     */
    public synchronized boolean markSubTaskCompleted(String taskId) {
        SubTask task = subTasks.get(taskId);
        if (task != null) {
            boolean wasAlreadyCompleted = (task.getStatus() == SubTaskStatus.COMPLETED);
            task.setStatus(SubTaskStatus.COMPLETED);
            task.setProgressPercentage(100.0);
            if (task.getCompletedTimestamp() <= 0) {
                task.setCompletedTimestamp(System.currentTimeMillis());
            }
            pendingSubTasks.remove(task);

            if (!wasAlreadyCompleted) {
                completedTaskCount.incrementAndGet();
            }
        }

        boolean allDone = isAllCompleted();
        if (allDone) {
            this.status = JobStatus.COMPLETED;
            if (this.completedTimestamp <= 0) {
                this.completedTimestamp = System.currentTimeMillis();
            }
        }
        return allDone;
    }

    /**
     * Checks if all sub-tasks or all frames have finished.
     */
    public boolean isAllCompleted() {
        if (status == JobStatus.COMPLETED) return true;
        if (!subTasks.isEmpty() && completedTaskCount.get() >= subTasks.size()) return true;
        return isAllFramesCovered();
    }

    /**
     * Computes the job progress percentage (0.0% to 100.0%) based on frame coverage.
     */
    public double getProgressPercentage() {
        if (status == JobStatus.COMPLETED) return 100.0;
        if (totalFrames > 0) {
            int count = 0;
            boolean[] covered = new boolean[totalFrames + 1];
            for (SubTask st : subTasks.values()) {
                if (st.getStatus() == SubTaskStatus.COMPLETED) {
                    for (int f = Math.max(1, st.getStartFrame()); f <= Math.min(totalFrames, st.getEndFrame()); f++) {
                        if (!covered[f]) {
                            covered[f] = true;
                            count++;
                        }
                    }
                }
            }
            return Math.min(100.0, ((double) count / totalFrames) * 100.0);
        }
        if (subTasks.isEmpty()) return 0.0;
        return ((double) completedTaskCount.get() / subTasks.size()) * 100.0;
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

    public String getJobId() { return jobId; }
    public String getJobName() { return jobName; }
    public String getWorkloadType() { return workloadType; }
    public int getTotalFrames() { return totalFrames; }
    public long getSubmissionTimestamp() { return submissionTimestamp; }
    public long getCompletedTimestamp() { return completedTimestamp; }
    public void setCompletedTimestamp(long completedTimestamp) { this.completedTimestamp = completedTimestamp; }
    public Map<String, Object> getParameters() { return parameters; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getCompiledVideoUrl() { return compiledVideoUrl; }
    public void setCompiledVideoUrl(String compiledVideoUrl) { this.compiledVideoUrl = compiledVideoUrl; }
    public String getVideoUrl() { return compiledVideoUrl; }
    public void setVideoUrl(String videoUrl) { this.compiledVideoUrl = videoUrl; }

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
        private final String renderEngine;

        private volatile SubTaskStatus status;
        private volatile String assignedWorkerId;
        private volatile int retryCount;
        private volatile byte[] taskPayloadBytes;
        private volatile Object taskData;
        private volatile double progressPercentage; // Track individual subtask progress
        private volatile long dispatchedTimestamp = 0;
        private volatile long completedTimestamp = 0;

        public SubTask(String taskId, String jobId, int startFrame, int endFrame, String frameRange, String workloadType, String renderEngine) {
            this.taskId = taskId;
            this.jobId = jobId;
            this.startFrame = startFrame;
            this.endFrame = endFrame;
            this.frameRange = frameRange;
            this.workloadType = workloadType;
            this.renderEngine = renderEngine;
            this.status = SubTaskStatus.PENDING;
            this.retryCount = 0;
            this.progressPercentage = 0.0;
        }

        public String getTaskId() { return taskId; }
        public String getJobId() { return jobId; }
        public int getStartFrame() { return startFrame; }
        public int getEndFrame() { return endFrame; }
        public String getFrameRange() { return frameRange; }
        public String getWorkloadType() { return workloadType; }
        public String getRenderEngine() { return renderEngine; }
        
        public SubTaskStatus getStatus() { return status; }
        public void setStatus(SubTaskStatus status) { this.status = status; }

        public String getAssignedWorkerId() { return assignedWorkerId; }
        public void setAssignedWorkerId(String workerId) { this.assignedWorkerId = workerId; }

        public int getRetryCount() { return retryCount; }
        public void incrementRetryCount() { this.retryCount++; }

        public byte[] getTaskPayloadBytes() { return taskPayloadBytes; }
        public void setTaskPayloadBytes(byte[] taskPayloadBytes) { this.taskPayloadBytes = taskPayloadBytes; }

        public Object getTaskData() { return taskData; }
        public void setTaskData(Object taskData) { this.taskData = taskData; }

        public double getProgressPercentage() { return progressPercentage; }
        public void setProgressPercentage(double pct) { this.progressPercentage = pct; }

        public long getDispatchedTimestamp() { return dispatchedTimestamp; }
        public void setDispatchedTimestamp(long dispatchedTimestamp) { this.dispatchedTimestamp = dispatchedTimestamp; }

        public long getCompletedTimestamp() { return completedTimestamp; }
        public void setCompletedTimestamp(long completedTimestamp) { this.completedTimestamp = completedTimestamp; }

        public long getExecutionDurationMs() {
            if (completedTimestamp > dispatchedTimestamp && dispatchedTimestamp > 0) {
                return completedTimestamp - dispatchedTimestamp;
            }
            return 0;
        }

        private volatile boolean isStolen = false;
        private volatile String stolenFromWorkerId = null;

        public boolean isStolen() { return isStolen; }
        public void setStolen(boolean stolen) { this.isStolen = stolen; }

        public String getStolenFromWorkerId() { return stolenFromWorkerId; }
        public void setStolenFromWorkerId(String workerId) { this.stolenFromWorkerId = workerId; }

        @Override
        public String toString() {
            return String.format("SubTask[ID=%s, Range=%s, Status=%s, Worker=%s, Retries=%d, Dur=%dms%s]",
                taskId, frameRange, status, assignedWorkerId != null ? assignedWorkerId : "NONE", retryCount, getExecutionDurationMs(),
                isStolen ? " (Stolen from " + stolenFromWorkerId + ")" : "");
        }
    }
}
