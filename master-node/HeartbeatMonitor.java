import com.campusgrid.core.*;

/**
 * CAMPUS GRID - HEARTBEAT MONITOR SERVICE
 * 
 * An asynchronous background watchdog daemon that continuously audits registered
 * worker nodes for active heartbeat signals.
 * 
 * When a worker node fails to transmit a heartbeat packet within the designated timeout
 * threshold (default: 15 seconds), the monitor:
 * 1. Flags the worker node as OFFLINE.
 * 2. Unbinds and releases any in-flight sub-tasks / frame ranges back to the JobManager queue.
 * 3. Safely tears down the unresponsive network socket to release OS resources.
 */
public class HeartbeatMonitor implements Runnable {

    private static final long DEFAULT_TIMEOUT_THRESHOLD_MS = 35000; // 35 seconds
    private static final long DEFAULT_CHECK_INTERVAL_MS = 5000;      // 5 seconds

    private final WorkerRegistry workerRegistry;
    private final JobManager jobManager;
    private final long timeoutThresholdMs;
    private final long checkIntervalMs;

    private volatile boolean running = false;
    private Thread monitorThread;

    /**
     * Constructs a HeartbeatMonitor with default 15s timeout and 5s polling intervals.
     *
     * @param workerRegistry The central WorkerRegistry to inspect.
     * @param jobManager The JobManager to receive re-queued tasks.
     */
    public HeartbeatMonitor(WorkerRegistry workerRegistry, JobManager jobManager) {
        this(workerRegistry, jobManager, DEFAULT_TIMEOUT_THRESHOLD_MS, DEFAULT_CHECK_INTERVAL_MS);
    }

    /**
     * Constructs a HeartbeatMonitor with customized timeout and check intervals.
     *
     * @param workerRegistry The central WorkerRegistry.
     * @param jobManager The central JobManager.
     * @param timeoutThresholdMs Heartbeat expiration threshold in milliseconds.
     * @param checkIntervalMs Watchdog audit frequency in milliseconds.
     */
    public HeartbeatMonitor(WorkerRegistry workerRegistry, JobManager jobManager, long timeoutThresholdMs, long checkIntervalMs) {
        this.workerRegistry = workerRegistry;
        this.jobManager = jobManager;
        this.timeoutThresholdMs = Math.max(2000, timeoutThresholdMs);
        this.checkIntervalMs = Math.max(1000, checkIntervalMs);
    }

    /**
     * Starts the heartbeat watchdog daemon thread.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        monitorThread = new Thread(this, "HeartbeatMonitor-Daemon");
        monitorThread.setDaemon(true);
        monitorThread.start();
        System.out.printf("[HEARTBEAT-MONITOR] Watchdog active (Timeout limit: %ds, Poll interval: %ds).\n",
            timeoutThresholdMs / 1000, checkIntervalMs / 1000);
    }

    /**
     * Alias for start() to match standard service lifecycles.
     */
    public void startMonitor() {
        start();
    }

    /**
     * Gracefully halts the watchdog monitor.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        System.out.println("[HEARTBEAT-MONITOR] Watchdog stopped.");
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                auditWorkerHeartbeats();
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[HEARTBEAT-MONITOR-ERR] Exception during heartbeat audit: " + e.getMessage());
            }
        }
    }

    /**
     * Audits all workers in the registry and triggers recovery on dead nodes.
     */
    private void auditWorkerHeartbeats() {
        long now = System.currentTimeMillis();

        for (WorkerState worker : workerRegistry.getAllWorkers()) {
            WorkerStatus status = worker.getStatus();

            // Skip nodes already marked OFFLINE or EVICTED
            if (status == WorkerStatus.OFFLINE || status == WorkerStatus.EVICTED) {
                continue;
            }

            long lastHeartbeat = worker.getLastHeartbeatTimestamp();
            long elapsedSinceLastBeat = now - lastHeartbeat;

            if (elapsedSinceLastBeat > timeoutThresholdMs) {
                String workerId = worker.getWorkerId();
                System.out.printf("[HEARTBEAT-MONITOR] ⚠ Dead node detected: Worker [%s] missed heartbeats (Last seen %.1fs ago).\n",
                    workerId, elapsedSinceLastBeat / 1000.0);

                // Trigger atomic crash recovery, socket cleanup, and task re-queuing
                workerRegistry.handleWorkerFailure(workerId, jobManager);
            }
        }
    }
}
