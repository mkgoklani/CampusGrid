import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * CAMPUS GRID - NON-BLOCKING BASIC SCHEDULER
 * 
 * An asynchronous background engine that continuously polls the JobManager queue
 * and pairs pending frame-range sub-tasks with available (IDLE) worker nodes from
 * the WorkerRegistry.
 * 
 * Non-Blocking Design:
 * Unlike Phase 1 static CountDownLatch barriers, this scheduler operates in a continuous
 * non-blocking loop, allowing fast workers to complete tasks and receive new work immediately
 * without waiting for slower or throttled nodes.
 */
public class BasicScheduler implements Runnable {

    private static final int DEFAULT_POLL_INTERVAL_MS = 500;

    private final JobManager jobManager;
    private final WorkerRegistry workerRegistry;
    private final int pollIntervalMs;
    private WorkerReliabilityTracker reliabilityTracker;

    private volatile boolean running = false;
    private Thread schedulerThread;

    /**
     * Constructs a BasicScheduler with default 500ms polling intervals.
     *
     * @param jobManager The central JobManager managing active jobs.
     * @param workerRegistry The WorkerRegistry tracking connected nodes.
     */
    public BasicScheduler(JobManager jobManager, WorkerRegistry workerRegistry) {
        this(jobManager, workerRegistry, DEFAULT_POLL_INTERVAL_MS);
    }

    /**
     * Constructs a BasicScheduler with a customized polling interval.
     *
     * @param jobManager The central JobManager managing active jobs.
     * @param workerRegistry The WorkerRegistry tracking connected nodes.
     * @param pollIntervalMs The polling cycle delay in milliseconds.
     */
    public BasicScheduler(JobManager jobManager, WorkerRegistry workerRegistry, int pollIntervalMs) {
        this.jobManager = jobManager;
        this.workerRegistry = workerRegistry;
        this.pollIntervalMs = Math.max(100, pollIntervalMs);
    }

    /**
     * Sets the optional WorkerReliabilityTracker for reliability-informed scheduling.
     */
    public void setReliabilityTracker(WorkerReliabilityTracker tracker) {
        this.reliabilityTracker = tracker;
    }

    /**
     * Starts the non-blocking scheduler loop in a dedicated background daemon thread.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        schedulerThread = new Thread(this, "BasicScheduler-Daemon");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
        System.out.println("[SCHEDULER] Non-blocking scheduler loop started (Interval: " + pollIntervalMs + "ms).");
    }

    /**
     * Alias for start() to match standard scheduler loop lifecycle.
     */
    public void startSchedulerLoop() {
        start();
    }

    /**
     * Gracefully stops the scheduler background thread.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (schedulerThread != null) {
            schedulerThread.interrupt();
            schedulerThread = null;
        }
        System.out.println("[SCHEDULER] Scheduler loop stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                schedulePendingTasks();
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[SCHEDULER-ERR] Exception in scheduling cycle: " + e.getMessage());
            }
        }
    }

    /**
     * Core non-blocking dispatch logic.
     * Queries available idle nodes, fetches pending sub-tasks, and streams them over TCP.
     */
    private void schedulePendingTasks() {
        List<WorkerState> availableWorkers = workerRegistry.getAvailableWorkers();
        if (availableWorkers.isEmpty()) {
            return;
        }

        // Hardware-Aware Capability & Reliability Load Balancing:
        // Prioritize fastest compute nodes (GPUs) first, factoring reliability and thermal headroom
        availableWorkers.sort((w1, w2) -> {
            double comp1 = ComputeCapabilityEngine.calculateScore(w1);
            double comp2 = ComputeCapabilityEngine.calculateScore(w2);

            double rel1 = (reliabilityTracker != null) ? reliabilityTracker.getReliabilityScore(w1.getWorkerId()) : w1.getReliabilityScore();
            double rel2 = (reliabilityTracker != null) ? reliabilityTracker.getReliabilityScore(w2.getWorkerId()) : w2.getReliabilityScore();
            
            double tempFactor1 = 1.0 - Math.min(1.0, (double) w1.getCpuTemperature() / 90.0);
            double tempFactor2 = 1.0 - Math.min(1.0, (double) w2.getCpuTemperature() / 90.0);

            // Compute score (4.5 vs 1.0) is the dominant factor (0.6), followed by reliability (0.25) and thermals (0.15)
            double p1 = (comp1 * 0.6) + (rel1 * 0.25) + (tempFactor1 * 0.15);
            double p2 = (comp2 * 0.6) + (rel2 * 0.25) + (tempFactor2 * 0.15);
            return Double.compare(p2, p1); // Highest capability node dispatched first
        });

        for (WorkerState worker : availableWorkers) {
            // Re-verify worker state in case a concurrent thread changed it
            if (worker.getStatus() != WorkerStatus.IDLE || worker.getSocket().isClosed()) {
                continue;
            }

            // Fetch hardware-matched pending task from active job queue
            Job.SubTask task = jobManager.getNextPendingTaskForWorker(worker);
            if (task == null) {
                // Adaptive Dynamic Work Stealing: Steal from active stragglers
                task = stealWorkForIdleWorker(worker);
            }

            if (task == null) {
                // No more tasks or stealable work in this cycle
                break;
            }

            dispatchTaskToWorker(worker, task);
        }
    }

    /**
     * Adaptive Dynamic Work Stealing (Speculative Execution Model):
     * 
     * Identifies active workers with large remaining frame slices and splits the
     * unfinished upper half to assign to an idle worker immediately.
     * 
     * NOTE: This implements a "speculative execution" model similar to Google MapReduce.
     * The original task's endFrame is NOT shrunk because the Blender process is already
     * running with the full range. Both the original and stolen task may render overlapping
     * frames. ResultCollector uses TRUNCATE_EXISTING, so whichever finishes last simply
     * overwrites — no data corruption occurs. This trades ~10-15% redundant compute for
     * significantly reduced tail latency on straggler nodes.
     * 
     * Adaptive Threshold: Faster idle nodes (by ComputeCapabilityEngine score) can steal
     * smaller chunks, and the steal ratio is proportional to the score advantage.
     */
    private Job.SubTask stealWorkForIdleWorker(WorkerState idleWorker) {
        Job activeJob = jobManager.getCurrentActiveJob();
        if (activeJob == null || activeJob.getStatus() != JobStatus.RUNNING || activeJob.isAllFramesCovered()) {
            return null;
        }

        double idleScore = ComputeCapabilityEngine.calculateScore(idleWorker);

        synchronized (activeJob) {
            for (Job.SubTask runningTask : activeJob.getSubTasks()) {
                if (runningTask.getStatus() == Job.SubTaskStatus.DISPATCHED 
                    && runningTask.getAssignedWorkerId() != null 
                    && !runningTask.getAssignedWorkerId().equals(idleWorker.getWorkerId())) {
                    
                    WorkerState busyWorker = workerRegistry.getWorker(runningTask.getAssignedWorkerId());
                    double busyScore = (busyWorker != null) ? ComputeCapabilityEngine.calculateScore(busyWorker) : 1.0;

                    // 1. Anti-Snatching: Only steal from genuine stragglers running >= 12 seconds
                    // (unless the idle worker is a high-speed GPU with 2x+ compute capability)
                    long elapsed = System.currentTimeMillis() - runningTask.getDispatchTimestamp();
                    if (elapsed < 12000 && idleScore < busyScore * 2.0) {
                        continue;
                    }

                    // 2. Anti-Snatching: Max 1 speculative steal per running parent task
                    int existingSteals = 0;
                    for (Job.SubTask existingSt : activeJob.getSubTasks()) {
                        if (existingSt.isStolen() && runningTask.getAssignedWorkerId().equals(existingSt.getStolenFromWorkerId())) {
                            existingSteals++;
                        }
                    }
                    if (existingSteals >= 1 && idleScore <= busyScore) {
                        // Prevent equal-capability CPU ping-pong re-stealing
                        continue;
                    }

                    // 3. Calculate the highest un-stolen frame bound for this task
                    int effectiveEndFrame = runningTask.getEndFrame();
                    for (Job.SubTask existingSt : activeJob.getSubTasks()) {
                        if (existingSt.isStolen() && runningTask.getAssignedWorkerId().equals(existingSt.getStolenFromWorkerId())) {
                            if (existingSt.getStartFrame() <= effectiveEndFrame && existingSt.getStartFrame() > runningTask.getStartFrame()) {
                                effectiveEndFrame = Math.min(effectiveEndFrame, existingSt.getStartFrame() - 1);
                            }
                        }
                    }

                    int currentRenderedFrame = (busyWorker != null) ? busyWorker.getLatestFrameNumber() : runningTask.getStartFrame();
                    int unrenderedStart = Math.max(runningTask.getStartFrame(), currentRenderedFrame);
                    int remainingFrames = effectiveEndFrame - unrenderedStart + 1;

                    // Minimum slice size to justify speculative steal (avoid micro-slices)
                    int minStealable = (idleScore >= busyScore * 2.0) ? 4 : 8;

                    if (remainingFrames >= minStealable && effectiveEndFrame >= unrenderedStart) {
                        // Proportional steal: faster idle node gets a proportional share of un-stolen remainder
                        double stealRatio = idleScore / (idleScore + busyScore);
                        int splitCount = Math.max(2, (int)(remainingFrames * stealRatio));
                        int splitStart = effectiveEndFrame - splitCount + 1;
                        int splitEnd = effectiveEndFrame;

                        if (splitStart > splitEnd || splitStart < unrenderedStart) {
                            continue;
                        }

                        String stolenTaskId = String.format("%s_ST%03d", activeJob.getJobId(), activeJob.getSubTaskCount() + 1);
                        String stolenRange = (splitStart == splitEnd) ? String.valueOf(splitStart) : (splitStart + "-" + splitEnd);

                        Job.SubTask stolenTask = new Job.SubTask(stolenTaskId, activeJob.getJobId(), splitStart, splitEnd, stolenRange, activeJob.getWorkloadType());
                        stolenTask.setStolen(true);
                        stolenTask.setStolenFromWorkerId(runningTask.getAssignedWorkerId());
                        stolenTask.setTaskData(runningTask.getTaskData());
                        stolenTask.setTaskPayloadBytes(runningTask.getTaskPayloadBytes());

                        activeJob.addStolenSubTask(stolenTask);
                        System.out.printf("[SCHEDULER] ⚡ Dynamic Work-Steal: Worker [%s] (score=%.1f) stole Frames %s from straggler Worker [%s] (score=%.1f, elapsed=%.1fs, %d remaining)\n",
                            idleWorker.getWorkerId(), idleScore, stolenRange, runningTask.getAssignedWorkerId(), busyScore, elapsed / 1000.0, remainingFrames);

                        return activeJob.pollPendingSubTask();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Dispatches a single sub-task to the chosen worker over TCP ObjectOutputStream.
     */
    private void dispatchTaskToWorker(WorkerState worker, Job.SubTask task) {
        String workerId = worker.getWorkerId();
        String jobId = task.getJobId();
        String taskId = task.getTaskId();
        String frameRange = task.getFrameRange();

        // 1. Atomically mark worker BUSY and bind task details in registry
        workerRegistry.assignTaskToWorker(workerId, jobId, taskId, frameRange);
        task.setAssignedWorkerId(workerId);
        task.setStatus(Job.SubTaskStatus.DISPATCHED);
        task.setDispatchTimestamp(System.currentTimeMillis());

        // 2. Build protocol envelope with configured render engine and quality settings
        Job parentJob = jobManager.getAllJobs().get(jobId);
        String renderEngine = "CYCLES";
        int renderSamples = 64;
        boolean useDenoising = true;
        int resolutionPercentage = 100;

        if (parentJob != null && parentJob.getParameters() != null) {
            Map<String, Object> params = parentJob.getParameters();
            if (params.containsKey("renderEngine")) {
                renderEngine = params.get("renderEngine").toString();
            }
            if (params.containsKey("renderSamples")) {
                try { renderSamples = Integer.parseInt(params.get("renderSamples").toString()); } catch (Exception ignored) {}
            }
            if (params.containsKey("useDenoising")) {
                try { useDenoising = Boolean.parseBoolean(params.get("useDenoising").toString()); } catch (Exception ignored) {}
            }
            if (params.containsKey("resolutionPercentage")) {
                try { resolutionPercentage = Integer.parseInt(params.get("resolutionPercentage").toString()); } catch (Exception ignored) {}
            }
        }

        TaskAssignmentPayload payload = new TaskAssignmentPayload(
            jobId,
            taskId,
            task.getWorkloadType(),
            task.getTaskData() != null ? task.getTaskData() : task.getTaskPayloadBytes(),
            frameRange,
            renderEngine,
            renderSamples,
            useDenoising,
            resolutionPercentage
        );

        GridMessage message = new GridMessage(
            MessageType.SUBMIT_TASK,
            "MASTER_CONTROL_PLANE",
            payload
        );

        // 3. Transmit across wire
        ObjectOutputStream outStream = worker.getOutStream();
        try {
            synchronized (outStream) {
                outStream.writeObject(message);
                outStream.flush();
                outStream.reset(); // Prevent object stream memory leaks
            }

            System.out.printf("[SCHEDULER] ➔ Dispatched Task [%s] (Frames: %s) to Worker [%s] (Temp: %d°C)\n",
                taskId, frameRange, workerId, worker.getCpuTemperature());

        } catch (IOException e) {
            // Fault Tolerance: Worker dropped during dispatch
            System.err.printf("[SCHEDULER-WARN] ⚠ Failed to send Task [%s] to Worker [%s]: %s\n",
                taskId, workerId, e.getMessage());

            // Handle worker failure and re-queue task immediately for another worker
            workerRegistry.handleWorkerFailure(workerId, jobManager);
        }
    }
}
