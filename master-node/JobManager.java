import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * CAMPUS GRID - ASYNCHRONOUS JOB MANAGER
 * 
 * Thread-safe orchestrator managing job lifecycles, frame-range task dispatch queues,
 * and distributed execution progress across the worker grid.
 */
public class JobManager {

    private final ConcurrentLinkedQueue<Job> pendingJobQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, Job> jobRegistry = new ConcurrentHashMap<>();
    private volatile Job currentActiveJob = null;

    /**
     * Submits a new job to the manager. Slices into default frame slices (25 frames)
     * if not already partitioned, and stores in the job registry and pending queue.
     *
     * @param job The Job instance to submit.
     */
    public void submitJob(Job job) {
        submitJob(job, 25);
    }

    /**
     * Submits a new job and partitions it into specified frame ranges per sub-task.
     *
     * @param job The Job instance to submit.
     * @param framesPerTask Number of frames allocated per worker task.
     */
    public void submitJob(Job job, int framesPerTask) {
        if (job == null) return;

        if (job.getSubTaskCount() == 0) {
            job.sliceIntoFrameRanges(framesPerTask);
        }

        job.setStatus(JobStatus.QUEUED);
        jobRegistry.put(job.getJobId(), job);
        pendingJobQueue.add(job);
        System.out.println("[JOB-MANAGER] Enqueued job [" + job.getJobId() + "] with " + job.getSubTaskCount() + " sub-tasks.");
    }

    /**
     * Retrieves the next pending SubTask available across running and queued jobs.
     * Advances through the queued jobs automatically.
     *
     * @return Next available Job.SubTask, or null if no tasks are pending.
     */
    public synchronized Job.SubTask getNextPendingTask() {
        // 1. Check current active job for pending tasks
        if (currentActiveJob != null && currentActiveJob.getStatus() == JobStatus.RUNNING) {
            Job.SubTask task = currentActiveJob.pollPendingSubTask();
            if (task != null) {
                task.setStatus(Job.SubTaskStatus.DISPATCHED);
                return task;
            }
        }

        // 2. Check all other running jobs for re-queued/pending tasks (fault tolerance recovery)
        for (Job job : jobRegistry.values()) {
            if (job.getStatus() == JobStatus.RUNNING && (currentActiveJob == null || !job.getJobId().equals(currentActiveJob.getJobId()))) {
                Job.SubTask task = job.pollPendingSubTask();
                if (task != null) {
                    task.setStatus(Job.SubTaskStatus.DISPATCHED);
                    return task;
                }
            }
        }

        // 3. If no active/running jobs have pending tasks, advance queued jobs
        while (!pendingJobQueue.isEmpty()) {
            Job nextJob = pendingJobQueue.poll();
            if (nextJob != null && nextJob.getStatus() == JobStatus.QUEUED) {
                nextJob.setStatus(JobStatus.RUNNING);
                currentActiveJob = nextJob;
                System.out.println("[JOB-MANAGER] Switched active job to [" + nextJob.getJobId() + "]");

                Job.SubTask task = currentActiveJob.pollPendingSubTask();
                if (task != null) {
                    task.setStatus(Job.SubTaskStatus.DISPATCHED);
                    return task;
                }
            }
        }

        return null;
    }

    /**
     * Retrieves the best hardware-matched pending SubTask for a requesting worker.
     * High-spec workers (GPUs) receive the larger slices, while lower-spec workers (CPUs)
     * receive smaller slices to balance execution times.
     *
     * @param worker The target worker node.
     * @return Hardware-matched SubTask, or null if no tasks are available.
     */
    public synchronized Job.SubTask getNextPendingTaskForWorker(WorkerState worker) {
        if (worker == null) return getNextPendingTask();
        double workerScore = ComputeCapabilityEngine.calculateScore(worker);

        // 1. Check current active job
        if (currentActiveJob != null && currentActiveJob.getStatus() == JobStatus.RUNNING) {
            Job.SubTask task = currentActiveJob.pollBestSubTaskForWorker(workerScore);
            if (task != null) {
                task.setStatus(Job.SubTaskStatus.DISPATCHED);
                return task;
            }
        }

        // 2. Check all other running jobs
        for (Job job : jobRegistry.values()) {
            if (job.getStatus() == JobStatus.RUNNING && (currentActiveJob == null || !job.getJobId().equals(currentActiveJob.getJobId()))) {
                Job.SubTask task = job.pollBestSubTaskForWorker(workerScore);
                if (task != null) {
                    task.setStatus(Job.SubTaskStatus.DISPATCHED);
                    return task;
                }
            }
        }

        // 3. Advance queued jobs
        while (!pendingJobQueue.isEmpty()) {
            Job nextJob = pendingJobQueue.poll();
            if (nextJob != null && nextJob.getStatus() == JobStatus.QUEUED) {
                nextJob.setStatus(JobStatus.RUNNING);
                currentActiveJob = nextJob;
                System.out.println("[JOB-MANAGER] Switched active job to [" + nextJob.getJobId() + "]");

                Job.SubTask task = currentActiveJob.pollBestSubTaskForWorker(workerScore);
                if (task != null) {
                    task.setStatus(Job.SubTaskStatus.DISPATCHED);
                    return task;
                }
            }
        }

        return null;
    }

    /**
     * Updates sub-task execution status and marks the job COMPLETED when all sub-tasks finish.
     *
     * @param jobId The parent job identifier.
     * @param taskId The sub-task identifier.
     * @param completed true if task completed successfully, false if failed.
     */
    public void updateJobProgress(String jobId, String taskId, boolean completed) {
        Job job = jobRegistry.get(jobId);
        if (job == null) return;

        if (completed) {
            boolean allDone = job.markSubTaskCompleted(taskId);
            System.out.printf("[JOB-MANAGER] Job [%s] task [%s] COMPLETED (Progress: %.1f%%)\n",
                jobId, taskId, job.getProgressPercentage());

            if (allDone) {
                System.out.println("[JOB-MANAGER] ★★★ Job [" + jobId + "] FULLY COMPLETED ★★★");
                if (currentActiveJob != null && currentActiveJob.getJobId().equals(jobId)) {
                    currentActiveJob = null;
                }

                // Automatic Post-Processing, ZIP bundling & Video Compilation
                new Thread(() -> {
                    boolean cleanUp = false;
                    if (job.getParameters() != null && job.getParameters().containsKey("deleteFramesAfterStitch")) {
                        cleanUp = Boolean.parseBoolean(job.getParameters().get("deleteFramesAfterStitch").toString());
                    }
                    FrameStitcher stitcher = new FrameStitcher();
                    stitcher.processJobOutput(jobId, job.getTotalFrames(), 30, cleanUp);
                }, "FrameStitcher-" + jobId).start();
            }
        } else {
            // Task failed - requeue for retry up to 3 times
            for (Job.SubTask task : job.getSubTasks()) {
                if (task.getTaskId().equals(taskId)) {
                    if (task.getRetryCount() >= 3) {
                        System.err.printf("[JOB-MANAGER-ERR] Task [%s] failed on retry limit (Attempt %d). Marking job FAILED.\n",
                            taskId, task.getRetryCount());
                        task.setStatus(Job.SubTaskStatus.FAILED);
                        job.setStatus(JobStatus.FAILED);
                        if (currentActiveJob != null && currentActiveJob.getJobId().equals(jobId)) {
                            currentActiveJob = null;
                        }
                    } else {
                        System.out.printf("[JOB-MANAGER-WARN] Task [%s] failed. Re-queuing for retry (Attempt %d/3)...\n",
                            taskId, task.getRetryCount() + 1);
                        job.requeueSubTask(task);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Cancels an active or queued job and cascades the CANCEL_TASK signal
     * to all active workers currently processing slices of this job.
     *
     * @param jobId Identifier of the job to cancel.
     * @param workerRegistry The WorkerRegistry to find active nodes and transmit cancellation frames.
     */
    public void cancelJob(String jobId, WorkerRegistry workerRegistry) {
        Job job = jobRegistry.get(jobId);
        if (job != null) {
            job.setStatus(JobStatus.CANCELLED);
            pendingJobQueue.remove(job);
            if (currentActiveJob != null && currentActiveJob.getJobId().equals(jobId)) {
                currentActiveJob = null;
            }
            System.out.println("[JOB-MANAGER] Job [" + jobId + "] CANCELLED by operator.");

            // Cascade cancellation to all active workers assigned to this job
            if (workerRegistry != null) {
                GridMessage cancelEnvelope = new GridMessage(
                    MessageType.CANCEL_TASK,
                    "MASTER_CONTROL_PLANE",
                    jobId
                );

                for (WorkerState worker : workerRegistry.getAllWorkers()) {
                    String assignedJob;
                    String assignedTask;
                    synchronized (worker) {
                        assignedJob = worker.getCurrentJobId();
                        assignedTask = worker.getCurrentTaskId();
                    }

                    if (jobId.equals(assignedJob)) {
                        System.out.printf("[JOB-MANAGER] ➔ Cascading CANCEL_TASK to Worker [%s] (Task: %s)...\n",
                            worker.getWorkerId(), assignedTask);

                        try {
                            java.io.ObjectOutputStream out = worker.getOutStream();
                            if (out != null) {
                                synchronized (out) {
                                    out.writeObject(cancelEnvelope);
                                    out.flush();
                                    out.reset();
                                }
                            }
                        } catch (java.io.IOException e) {
                            System.err.printf("[JOB-MANAGER-WARN] Failed sending cancel to Worker [%s]: %s\n",
                                worker.getWorkerId(), e.getMessage());
                        } finally {
                            // Free worker back to IDLE
                            synchronized (worker) {
                                if (worker.getStatus() == WorkerStatus.BUSY) {
                                    worker.setStatus(WorkerStatus.IDLE);
                                }
                                worker.setCurrentJobId(null);
                                worker.setCurrentTaskId(null);
                                worker.setAssignedFrameRange(null);
                            }
                        }
                    }
                }

                // Compile whatever frames were finished prior to cancellation for video preview
                new Thread(() -> {
                    try {
                        Thread.sleep(1500); // Wait for active file writes to flush
                        FrameStitcher stitcher = new FrameStitcher();
                        stitcher.stitchAvailableFrames(jobId, 30);
                    } catch (Exception ignored) {}
                }, "FrameStitcher-Cancel-" + jobId).start();
            }
        }
    }

    /**
     * Cancels a job without sending network packets (local cancellation).
     *
     * @param jobId Identifier of the job to cancel.
     */
    public void cancelJob(String jobId) {
        cancelJob(jobId, null);
    }

    /**
     * Retrieves a Job by its ID.
     */
    public Job getJob(String jobId) {
        return jobRegistry.get(jobId);
    }

    /**
     * Returns a snapshot map of all tracked jobs.
     */
    public Map<String, Job> getAllJobs() {
        return Collections.unmodifiableMap(jobRegistry);
    }

    public synchronized Job getCurrentActiveJob() {
        return currentActiveJob;
    }

    /**
     * Returns cluster job statistics.
     */
    public String getJobSummary() {
        int queued = 0, running = 0, completed = 0, cancelled = 0, failed = 0;
        for (Job j : jobRegistry.values()) {
            switch (j.getStatus()) {
                case QUEUED: queued++; break;
                case RUNNING: running++; break;
                case COMPLETED: completed++; break;
                case CANCELLED: cancelled++; break;
                case FAILED: failed++; break;
            }
        }
        return String.format("JobSummary[Total=%d, Queued=%d, Running=%d, Completed=%d, Cancelled=%d, Failed=%d]",
            jobRegistry.size(), queued, running, completed, cancelled, failed);
    }
}
