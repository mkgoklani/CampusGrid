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
            int chunk = framesPerTask > 0 ? framesPerTask : 25;
            job.sliceIntoFrameRanges(chunk);
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
            Job.SubTask task;
            while ((task = currentActiveJob.pollPendingSubTask()) != null) {
                if (task.getStatus() == Job.SubTaskStatus.COMPLETED) {
                    continue; // Skip already completed subtasks (e.g. from crash recovery)
                }
                task.setStatus(Job.SubTaskStatus.DISPATCHED);
                return task;
            }
        }

        // 2. Check all other running jobs for re-queued/pending tasks (fault tolerance recovery)
        for (Job job : jobRegistry.values()) {
            if (job.getStatus() == JobStatus.RUNNING && (currentActiveJob == null || !job.getJobId().equals(currentActiveJob.getJobId()))) {
                Job.SubTask task;
                while ((task = job.pollPendingSubTask()) != null) {
                    if (task.getStatus() == Job.SubTaskStatus.COMPLETED) {
                        continue; // Skip already completed subtasks
                    }
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

                Job.SubTask task;
                while ((task = currentActiveJob.pollPendingSubTask()) != null) {
                    if (task.getStatus() == Job.SubTaskStatus.COMPLETED) {
                        continue;
                    }
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
    public void updateJobProgress(String jobId, String taskId, boolean success) {
        Job job = jobRegistry.get(jobId);
        if (job == null) return;

        if (success) {
            boolean subTasksDone = job.markSubTaskCompleted(taskId);
            boolean framesCovered = job.isAllFramesCovered();
            boolean allDone = subTasksDone || framesCovered;

            System.out.printf("[JOB-MANAGER] Job [%s] task [%s] COMPLETED (Progress: %.1f%%, Frames Covered: %b)\n",
                jobId, taskId, job.getProgressPercentage(), framesCovered);

            if (allDone && job.tryStartPostProcessing()) {
                job.setStatus(JobStatus.COMPLETED);
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
            // If all frames are already rendered on disk, ignore speculative failures
            if (job.isAllFramesCovered()) {
                System.out.printf("[JOB-MANAGER] SubTask [%s] reported failure, but all %d frames are covered on disk. Job remains complete.\n",
                    taskId, job.getTotalFrames());
                if (job.getStatus() != JobStatus.COMPLETED) {
                    job.setStatus(JobStatus.COMPLETED);
                }
                return;
            }

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
     * Registers a job in the registry for tracking without automatically queueing it.
     */
    public void registerJob(Job job) {
        if (job != null) {
            jobRegistry.put(job.getJobId(), job);
        }
    }

    /**
     * Pauses an actively RUNNING or QUEUED job:
     * - Signals busy workers assigned to this job to halt active tasks (via CANCEL_TASK).
     * - Frees those workers back to IDLE.
     * - Keeps the unfinished sub-tasks intact for resumption.
     * - Sets job status to PAUSED and removes from pendingJobQueue.
     */
    public synchronized boolean pauseJob(String jobId, WorkerRegistry workerRegistry) {
        Job job = jobRegistry.get(jobId);
        if (job == null) return false;

        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED) {
            job.setStatus(JobStatus.PAUSED);
            pendingJobQueue.remove(job);
            if (currentActiveJob != null && currentActiveJob.getJobId().equals(jobId)) {
                currentActiveJob = null;
            }

            // Signal workers currently executing tasks for this job to halt and return to IDLE
            if (workerRegistry != null) {
                GridMessage cancelMsg = new GridMessage(
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
                        System.out.printf("[JOB-MANAGER] ⏸ Pausing active Task [%s] on Worker [%s]...\n",
                            assignedTask, worker.getWorkerId());

                        try {
                            java.io.ObjectOutputStream out = worker.getOutStream();
                            if (out != null) {
                                synchronized (out) {
                                    out.writeObject(cancelMsg);
                                    out.flush();
                                    out.reset();
                                }
                            }
                        } catch (java.io.IOException ignored) {}
                        finally {
                            synchronized (worker) {
                                if (worker.getStatus() == WorkerStatus.BUSY) {
                                    worker.setStatus(WorkerStatus.IDLE);
                                }
                                worker.setCurrentJobId(null);
                                worker.setCurrentTaskId(null);
                            }
                        }
                    }
                }
            }

            System.out.printf("[JOB-MANAGER] ⏸ Job [%s] PAUSED by operator.\n", jobId);
            return true;
        }
        return false;
    }

    /**
     * Resumes execution of a CANCELLED, PAUSED, or FAILED job by re-queuing all uncompleted sub-tasks.
     */
    public synchronized boolean resumeJob(String jobId) {
        Job job = jobRegistry.get(jobId);
        if (job == null) return false;

        // If all frames are covered or job is already finished, nothing to do
        if (job.isAllCompleted() || job.isAllFramesCovered()) {
            job.setStatus(JobStatus.COMPLETED);
            return false;
        }

        // Re-queue non-completed sub-tasks, checking on-disk frames to avoid re-rendering completed slices
        int requeuedCount = 0;
        for (Job.SubTask st : job.getSubTasks()) {
            if (st.getStatus() != Job.SubTaskStatus.COMPLETED) {
                job.requeueSubTask(st, false);
                if (st.getStatus() == Job.SubTaskStatus.PENDING) {
                    requeuedCount++;
                }
            }
        }

        if (requeuedCount == 0 || job.isAllCompleted() || job.isAllFramesCovered()) {
            job.setStatus(JobStatus.COMPLETED);
            System.out.printf("[JOB-MANAGER] ▶ Job [%s] RESUMED: all frames already present on disk. Marked COMPLETED.\n", jobId);
            return true;
        }

        job.setStatus(JobStatus.QUEUED);
        if (!pendingJobQueue.contains(job)) {
            pendingJobQueue.add(job);
        }
        System.out.printf("[JOB-MANAGER] ▶ Job [%s] RESUMED by operator (%d sub-tasks queued).\n",
            jobId, requeuedCount);
        return true;
    }

    /**
     * Permanently deletes a job from tracking and optionally removes its disk output directory.
     */
    public synchronized boolean deleteJob(String jobId, WorkerRegistry workerRegistry, boolean deleteFiles) {
        Job job = jobRegistry.remove(jobId);
        if (job == null) return false;

        // Cancel if active
        if (job.getStatus() == JobStatus.RUNNING || job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.PAUSED) {
            cancelJob(jobId, workerRegistry);
        }
        pendingJobQueue.remove(job);
        if (currentActiveJob != null && currentActiveJob.getJobId().equals(jobId)) {
            currentActiveJob = null;
        }

        // Optionally delete output files on disk
        if (deleteFiles) {
            try {
                java.io.File outDir = new java.io.File("./output/" + jobId);
                if (outDir.exists() && outDir.isDirectory()) {
                    deleteRecursively(outDir);
                }
            } catch (Exception e) {
                System.err.printf("[JOB-MANAGER-WARN] Failed deleting output directory for [%s]: %s\n", jobId, e.getMessage());
            }
        }

        System.out.printf("[JOB-MANAGER] 🗑 Job [%s] DELETED by operator (deleteFiles=%b).\n", jobId, deleteFiles);
        return true;
    }

    private static void deleteRecursively(java.io.File file) {
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    /**
     * Returns cluster job statistics.
     */
    public String getJobSummary() {
        int queued = 0, running = 0, paused = 0, completed = 0, cancelled = 0, failed = 0;
        for (Job j : jobRegistry.values()) {
            switch (j.getStatus()) {
                case QUEUED -> queued++;
                case RUNNING -> running++;
                case PAUSED -> paused++;
                case COMPLETED -> completed++;
                case CANCELLED -> cancelled++;
                case FAILED -> failed++;
            }
        }
        return String.format("JobSummary[Total=%d, Queued=%d, Running=%d, Paused=%d, Completed=%d, Cancelled=%d, Failed=%d]",
            jobRegistry.size(), queued, running, paused, completed, cancelled, failed);
    }
}
