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

        // Thermal Load Balancing: Prioritize dispatching to the coolest available worker
        availableWorkers.sort(Comparator.comparingInt(WorkerState::getCpuTemperature));

        for (WorkerState worker : availableWorkers) {
            // Re-verify worker state in case a concurrent thread changed it
            if (worker.getStatus() != WorkerStatus.IDLE || worker.getSocket().isClosed()) {
                continue;
            }

            // Fetch next pending task from active job queue
            Job.SubTask task = jobManager.getNextPendingTask();
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
     * Adaptive Dynamic Work Stealing:
     * Identifies active workers with large remaining frame slices (>= 4 frames)
     * and splits the unfinished upper half to assign to an idle worker immediately.
     */
    private synchronized Job.SubTask stealWorkForIdleWorker(WorkerState idleWorker) {
        Job activeJob = jobManager.getCurrentActiveJob();
        if (activeJob == null || activeJob.getStatus() != JobStatus.RUNNING) {
            return null;
        }

        for (Job.SubTask runningTask : activeJob.getSubTasks()) {
            if (runningTask.getStatus() == Job.SubTaskStatus.DISPATCHED 
                && runningTask.getAssignedWorkerId() != null 
                && !runningTask.getAssignedWorkerId().equals(idleWorker.getWorkerId())) {
                
                WorkerState busyWorker = workerRegistry.getWorker(runningTask.getAssignedWorkerId());
                int currentRenderedFrame = (busyWorker != null) ? busyWorker.getLatestFrameNumber() : runningTask.getStartFrame();
                int remainingFrames = runningTask.getEndFrame() - Math.max(runningTask.getStartFrame(), currentRenderedFrame);

                if (remainingFrames >= 4) {
                    int splitCount = remainingFrames / 2;
                    int splitStart = runningTask.getEndFrame() - splitCount + 1;
                    int splitEnd = runningTask.getEndFrame();

                    String stolenTaskId = String.format("%s_ST%03d", activeJob.getJobId(), activeJob.getSubTaskCount() + 1);
                    String stolenRange = (splitStart == splitEnd) ? String.valueOf(splitStart) : (splitStart + "-" + splitEnd);

                    Job.SubTask stolenTask = new Job.SubTask(stolenTaskId, activeJob.getJobId(), splitStart, splitEnd, stolenRange, activeJob.getWorkloadType());
                    stolenTask.setStolen(true);
                    stolenTask.setStolenFromWorkerId(runningTask.getAssignedWorkerId());
                    stolenTask.setTaskData(runningTask.getTaskData());
                    stolenTask.setTaskPayloadBytes(runningTask.getTaskPayloadBytes());

                    activeJob.addStolenSubTask(stolenTask);
                    System.out.printf("[SCHEDULER] ⚡ Dynamic Work-Steal: Worker [%s] stole Frames %s from lagging Worker [%s]!\n",
                        idleWorker.getWorkerId(), stolenRange, runningTask.getAssignedWorkerId());

                    return activeJob.pollPendingSubTask();
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
