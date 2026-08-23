import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Comparator;
import java.util.List;

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
                // No more tasks to dispatch in this cycle
                break;
            }

            dispatchTaskToWorker(worker, task);
        }
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

        // 2. Build protocol envelope
        TaskAssignmentPayload payload = new TaskAssignmentPayload(
            jobId,
            taskId,
            task.getWorkloadType(),
            task.getTaskPayloadBytes(),
            frameRange
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
