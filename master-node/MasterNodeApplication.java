import java.io.*;
import java.net.*;
import java.nio.file.Paths;
import java.util.concurrent.*;

/**
 * CAMPUS GRID - PHASE 2 MASTER NODE APPLICATION BOOTSTRAP
 * 
 * Central coordinator bringing together all 7 architectural modules:
 * 1. WorkerRegistry & WorkerState: Dynamic thread-safe cluster state tracking.
 * 2. Protocol Envelopes & DTOs: Typed GridMessage network serialization.
 * 3. JobManager: Asynchronous job queueing and frame-range slicing.
 * 4. BasicScheduler: Continuous non-blocking task dispatcher with thermal balancing.
 * 5. HeartbeatMonitor: Watchdog timeout scanner and automated crash recovery.
 * 6. ResultCollector: Binary frame artifact disk persistence (./output/{jobId}/).
 * 7. DashboardServer: Embedded REST API (Port 8081) and WebSocket streamer (Port 8082).
 */
public class MasterNodeApplication {

    public static final int AGENT_TCP_PORT = 8080;
    public static final int DASHBOARD_HTTP_PORT = 8081;
    public static final int DASHBOARD_WS_PORT = 8082;

    private final WorkerRegistry workerRegistry;
    private final JobManager jobManager;
    private final ResultCollector resultCollector;
    private final BasicScheduler scheduler;
    private final HeartbeatMonitor heartbeatMonitor;
    private final DashboardServer dashboardServer;
    private final LanDiscoveryResponder lanDiscovery;
    private final StateCheckpointManager checkpointManager;
    private final RenderETAEstimator etaEstimator;
    private final WorkerReliabilityTracker reliabilityTracker;
    private final ClusterUtilizationTracker utilizationTracker;

    private final int agentTcpPort;
    private final int dashboardHttpPort;
    private final int dashboardWsPort;

    private final ExecutorService agentThreadPool = Executors.newCachedThreadPool();
    private ServerSocket agentServerSocket;
    private volatile boolean running = false;

    public MasterNodeApplication() {
        this(AGENT_TCP_PORT, DASHBOARD_HTTP_PORT, DASHBOARD_WS_PORT);
    }

    public MasterNodeApplication(int agentTcpPort, int dashboardHttpPort, int dashboardWsPort) {
        this.agentTcpPort = agentTcpPort;
        this.dashboardHttpPort = dashboardHttpPort;
        this.dashboardWsPort = dashboardWsPort;

        // 1. Initialize State Storage & Queue Managers
        this.workerRegistry = new WorkerRegistry();
        this.jobManager = new JobManager();
        this.resultCollector = new ResultCollector(jobManager, workerRegistry, Paths.get("./output"));

        // 2. Initialize Analytics, Reliability & ETA Estimator
        this.reliabilityTracker = new WorkerReliabilityTracker();
        this.etaEstimator = new RenderETAEstimator();
        this.utilizationTracker = new ClusterUtilizationTracker(workerRegistry, jobManager, 3000);

        this.resultCollector.setETAEstimator(etaEstimator);
        this.resultCollector.setReliabilityTracker(reliabilityTracker);
        this.checkpointManager = new StateCheckpointManager(jobManager);

        // 3. Initialize Schedulers, Watchdogs, and LAN Discovery
        this.scheduler = new BasicScheduler(jobManager, workerRegistry, 500);
        this.scheduler.setReliabilityTracker(reliabilityTracker);
        this.heartbeatMonitor = new HeartbeatMonitor(workerRegistry, jobManager, 45000, 5000);
        this.lanDiscovery = new LanDiscoveryResponder(agentTcpPort);

        // 4. Initialize Embedded Web Dashboard Server
        this.dashboardServer = new DashboardServer(jobManager, workerRegistry, dashboardHttpPort, dashboardWsPort);
        this.dashboardServer.setETAEstimator(etaEstimator);
        this.dashboardServer.setReliabilityTracker(reliabilityTracker);
        this.dashboardServer.setUtilizationTracker(utilizationTracker);
        this.dashboardServer.setCheckpointManager(checkpointManager);
    }

    /**
     * Bootstraps and starts all Master Node subsystems.
     */
    public synchronized void start() throws IOException {
        if (running) return;
        running = true;

        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  CAMPUS GRID - COMPUTE PLATFORM V1 (PHASE 2 MASTER NODE)   ║");
        System.out.println("║  TCP Agent Port:    " + String.format("%-39d", agentTcpPort) + "║");
        System.out.println("║  HTTP REST API:     http://localhost:" + String.format("%-24d", dashboardHttpPort) + "║");
        System.out.println("║  WebSocket Stream:  ws://localhost:" + String.format("%-26d", dashboardWsPort) + "║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 1. Attempt Crash Recovery from previous checkpoint
        int restored = checkpointManager.restoreFromCheckpoint();
        if (restored > 0) {
            System.out.printf("[BOOTSTRAP] ★ Crash Recovery: %d job(s) restored from previous session!%n", restored);
        }

        // 2. Start Dashboard REST & WebSocket Services
        dashboardServer.start();

        // 3. Start Non-Blocking Scheduler Daemon
        scheduler.start();

        // 4. Start Watchdogs, Analytics, LAN Discovery, & State Checkpoint
        heartbeatMonitor.start();
        lanDiscovery.start();
        checkpointManager.start();
        utilizationTracker.start();

        // 5. Start TCP ServerSocket Listener for Agent Nodes
        agentServerSocket = new ServerSocket(agentTcpPort);
        Thread acceptThread = new Thread(this::runAgentAcceptLoop, "Master-Agent-Accept");
        acceptThread.setDaemon(false);
        acceptThread.start();

        // 6. Register JVM Shutdown Hook for Graceful Teardown
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Master-ShutdownHook"));

        System.out.println("[BOOTSTRAP] All Master Node modules, Analytics & State Persistence initialized and running successfully.");
        System.out.println();
    }

    /**
     * Listens for incoming Agent TCP socket connections and sets up streams.
     */
    private void runAgentAcceptLoop() {
        System.out.println("[NETWORK] Listening for Worker Agents on TCP port: " + agentServerSocket.getLocalPort());

        while (running && !agentServerSocket.isClosed()) {
            try {
                Socket socket = agentServerSocket.accept();
                String workerId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
                System.out.println("[REGISTRY] Inbound worker connection accepted: " + workerId);

                // Set socket read timeout to prevent indefinitely blocked receiver threads
                // when a worker process is killed without graceful TCP close (half-open state)
                socket.setSoTimeout(60_000); // 60-second read timeout

                // Initialize Object Streams: Flush output header immediately to prevent stream lock deadlock
                ObjectOutputStream outStream = new ObjectOutputStream(socket.getOutputStream());
                outStream.flush();
                ObjectInputStream inStream = new ObjectInputStream(socket.getInputStream());

                // Register worker into WorkerRegistry
                WorkerState workerState = new WorkerState(workerId, socket.getInetAddress().getHostAddress(), socket, outStream);
                workerRegistry.registerWorker(workerState);
                System.out.println("[REGISTRY] Worker [" + workerId + "] registered and marked IDLE.");

                // Spawn dedicated receiver thread for this worker connection
                agentThreadPool.submit(() -> runWorkerReceiverLoop(workerState, inStream));

            } catch (SocketException e) {
                if (!running) break; // Server shutting down
            } catch (IOException e) {
                System.err.println("[NETWORK-ERR] Error accepting agent connection: " + e.getMessage());
            }
        }
    }

    /**
     * Continuous packet receiver loop for an individual connected worker agent.
     */
    private void runWorkerReceiverLoop(WorkerState worker, ObjectInputStream inStream) {
        String workerId = worker.getWorkerId();

        try {
            while (running && !worker.getSocket().isClosed()) {
                Object obj = null;
                try {
                    obj = inStream.readObject();
                } catch (ClassNotFoundException cnfe) {
                    System.out.printf("[RECEIVER-WARN] Class not found on Master classpath from [%s]: %s (Stream continuing)\n", 
                        workerId, cnfe.getMessage());
                    continue;
                }

                if (obj == null) {
                    break;
                }

                if (obj instanceof GridMessage message) {
                    handleProtocolEnvelope(worker, message);
                } else if (obj != null && obj.getClass().getName().contains("RenderResult")) {
                    handleRenderResultPacket(worker, obj);
                } else if (obj != null && obj.getClass().getName().contains("BlenderStatusReport")) {
                    handleBlenderStatusPacket(worker, obj);
                } else if (obj instanceof String rawString) {
                    handleLegacyStringPacket(worker, rawString);
                } else {
                    System.out.println("[RECEIVER-WARN] Unrecognized packet received from [" + workerId + "]: " + obj.getClass());
                }
            }
        } catch (java.net.SocketTimeoutException ste) {
            // Socket read timeout: worker is unresponsive (half-open TCP)
            System.out.println("[NETWORK] Worker [" + workerId + "] timed out (60s read timeout).");
        } catch (EOFException | SocketException e) {
            // Normal client disconnect or socket closed
            System.out.println("[NETWORK] Worker [" + workerId + "] disconnected.");
        } catch (Exception e) {
            System.err.printf("[NETWORK-ERR] Stream exception on Worker [%s]: %s\n", workerId, e.getMessage());
        } finally {
            // Fault tolerance & Reliability: Record disconnect and trigger cleanup / auto-requeue
            if (reliabilityTracker != null) {
                reliabilityTracker.recordWorkerDisconnect(workerId);
            }
            workerRegistry.handleWorkerFailure(workerId, jobManager);
        }
    }

    /**
     * Routes structured GridMessage envelopes to appropriate subsystem handlers.
     */
    private void handleProtocolEnvelope(WorkerState worker, GridMessage message) {
        String workerId = worker.getWorkerId();
        MessageType type = message.getType();
        Object payload = message.getPayload();

        switch (type) {
            case HEARTBEAT -> {
                if (payload instanceof HeartbeatPayload hb) {
                    workerRegistry.updateTelemetry(workerId, hb.getCpuTemperature(), hb.getRamUsagePercent());
                    if (hb.getStatus() != null && worker.getStatus() != WorkerStatus.BUSY) {
                        workerRegistry.updateStatus(workerId, hb.getStatus());
                    }
                }
            }
            case TASK_PROGRESS -> {
                if (payload instanceof TaskProgressPayload prog) {
                    System.out.printf("[RECEIVER] Progress from [%s] on Task [%s]: %.1f%% (%s)\n",
                        workerId, prog.getTaskId(), prog.getProgressPercentage(), prog.getStatusMessage());
                }
            }
            case TASK_COMPLETE -> {
                if (payload instanceof TaskResultPayload result) {
                    System.out.printf("[RECEIVER] Result received for Task [%s] from Worker [%s]\n",
                        result.getTaskId(), workerId);
                    resultCollector.handleTaskResult(workerId, result);
                }
            }
            case EVICTED -> {
                System.out.println("[RECEIVER-WARN] ⚠ Eviction notification from Worker [" + workerId + "]");
                workerRegistry.handleWorkerFailure(workerId, jobManager);
                workerRegistry.updateStatus(workerId, WorkerStatus.EVICTED);
            }
            default -> System.out.println("[RECEIVER] Message type " + type + " received from [" + workerId + "]");
        }
    }

    private void handleRenderResultPacket(WorkerState worker, Object resultObj) {
        String workerId = worker.getWorkerId();
        try {
            Class<?> clazz = resultObj.getClass();
            String jobId = (String) clazz.getMethod("getJobId").invoke(resultObj);
            String status = (String) clazz.getMethod("getStatus").invoke(resultObj);
            boolean success = "SUCCESS".equalsIgnoreCase(status);

            java.util.Map<String, byte[]> frameBytesMap = null;
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, byte[]> frames = (java.util.Map<String, byte[]>) clazz.getMethod("getFrameBytesMap").invoke(resultObj);
                frameBytesMap = frames;
            } catch (Exception ignored) {}

            long duration = 0;
            try {
                duration = (long) clazz.getMethod("getRenderDuration").invoke(resultObj);
            } catch (Exception ignored) {}

            String taskId = worker.getCurrentTaskId() != null ? worker.getCurrentTaskId() : (jobId + "_T001");
            int frameCount = frameBytesMap != null ? frameBytesMap.size() : 0;

            if ("CHUNK".equalsIgnoreCase(status)) {
                System.out.printf("[RECEIVER] Stream Chunk for Job [%s] Task [%s] from [%s]: %d frames written to disk\n",
                    jobId, taskId, workerId, frameCount);
                resultCollector.saveFrameBinaries(jobId, frameBytesMap);

                // Dynamically update worker's live screen tile to the latest streamed frame
                if (frameBytesMap != null && !frameBytesMap.isEmpty()) {
                    int maxChunkFrame = -1;
                    for (String fname : frameBytesMap.keySet()) {
                        String digits = fname.replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) {
                            try {
                                int fn = Integer.parseInt(digits);
                                if (fn > maxChunkFrame) maxChunkFrame = fn;
                            } catch (Exception ignored) {}
                        }
                    }
                    if (maxChunkFrame > 0) {
                        worker.setLatestFrameNumber(maxChunkFrame);
                        worker.setLatestFrameUrl(String.format("/output/%s/frame_%04d.png", jobId, maxChunkFrame));
                    }
                }
                return; // Intermediate batch saved, wait for completion packet
            }

            System.out.printf("[RECEIVER] Final RenderResult for Job [%s] Task [%s] from [%s]: %s (%d frame binaries, %dms)\n",
                jobId, taskId, workerId, status, frameCount, duration);

            TaskResultPayload resultPayload = new TaskResultPayload(
                jobId, taskId, success, new byte[0], frameBytesMap, success ? null : "Render status: " + status, duration
            );
            resultCollector.handleTaskResult(workerId, resultPayload);
        } catch (Exception e) {
            System.err.println("[RECEIVER-ERR] Error unpacking RenderResult: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleBlenderStatusPacket(WorkerState worker, Object statusObj) {
        String workerId = worker.getWorkerId();
        try {
            Class<?> clazz = statusObj.getClass();
            String jobId = (String) clazz.getMethod("getJobId").invoke(statusObj);
            int currentFrame = (int) clazz.getMethod("getCurrentFrame").invoke(statusObj);
            int totalFrames = (int) clazz.getMethod("getTotalFrames").invoke(statusObj);
            double pct = (double) clazz.getMethod("getPercentage").invoke(statusObj);
            String state = (String) clazz.getMethod("getState").invoke(statusObj);
            String blenderVer = (String) clazz.getMethod("getBlenderVersion").invoke(statusObj);

            String tempStr = null;
            try {
                tempStr = (String) clazz.getMethod("getCpuTemperature").invoke(statusObj);
            } catch (Exception ignored) {}

            int temp = worker.getCpuTemperature();
            if (tempStr != null && !tempStr.isEmpty()) {
                try {
                    temp = Integer.parseInt(tempStr.replaceAll("[^0-9]", ""));
                } catch (Exception ignored) {}
            }

            double fps = -1.0;
            try {
                fps = (double) clazz.getMethod("getRenderFps").invoke(statusObj);
            } catch (Exception ignored) {}

            if (fps > 0) {
                worker.setLatestFps(fps);
            }

            if (currentFrame > 0 && jobId != null && !jobId.equalsIgnoreCase("N/A")) {
                worker.setLatestFrameNumber(currentFrame);
                java.io.File frameFile = new java.io.File(String.format("./output/%s/frame_%04d.png", jobId, currentFrame));
                if (frameFile.exists() && frameFile.length() > 0) {
                    worker.setLatestFrameUrl(String.format("/output/%s/frame_%04d.png", jobId, currentFrame));
                }
            }

            // CRITICAL: SoftwareEngine-1.0 fallback is NOT native Blender
            boolean isRealBlender = (blenderVer != null 
                && !blenderVer.equalsIgnoreCase("Unknown") 
                && !blenderVer.toLowerCase().contains("softwareengine")
                && !blenderVer.isEmpty());
            
            if (isRealBlender) {
                workerRegistry.updateEnvironment(workerId, null, true, blenderVer, -1.0);
            }

            workerRegistry.updateTelemetry(workerId, temp, worker.getRamUsagePercent());

            if ("RENDERING".equalsIgnoreCase(state) || "BUSY".equalsIgnoreCase(state)) {
                workerRegistry.updateStatus(workerId, WorkerStatus.BUSY);
            } else if ("READY".equalsIgnoreCase(state) || "IDLE".equalsIgnoreCase(state)) {
                if (worker.getCurrentJobId() == null) {
                    workerRegistry.updateStatus(workerId, WorkerStatus.IDLE);
                }
            }
        } catch (Exception e) {
            System.err.printf("[STATUS-ERR] Failed parsing BlenderStatusReport from [%s]: %s%n", workerId, e.getMessage());
        }
    }

    /**
     * Handles legacy String packets for backward compatibility with Phase 1 agents.
     */
    private void handleLegacyStringPacket(WorkerState worker, String raw) {
        String workerId = worker.getWorkerId();
        if (raw.startsWith("HEARTBEAT | TEMP: ")) {
            try {
                int temp = 36;
                double cpu = 0.0;
                double ram = 50.0;
                String os = null;
                String gpu = null;
                String blenderVer = null;
                boolean blenderInstalled = false;
                double installPct = -1.0;
                String cpuModel = null;
                String osArch = null;
                String agentVer = null;

                String[] parts = raw.split("\\|");
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("TEMP:")) {
                        String t = part.substring(5).replace("°C", "").trim();
                        temp = Integer.parseInt(t);
                    } else if (part.startsWith("CPU:")) {
                        String c = part.substring(4).replace("%", "").trim();
                        cpu = Double.parseDouble(c);
                    } else if (part.startsWith("RAM:")) {
                        String r = part.substring(4).replace("%", "").trim();
                        ram = Double.parseDouble(r);
                    } else if (part.startsWith("OS:")) {
                        os = part.substring(3).trim();
                    } else if (part.startsWith("GPU:")) {
                        gpu = part.substring(4).trim();
                    } else if (part.startsWith("BLENDER:")) {
                        blenderVer = part.substring(8).trim();
                        blenderInstalled = !"Unknown".equalsIgnoreCase(blenderVer) 
                            && !blenderVer.toLowerCase().contains("softwareengine") 
                            && !blenderVer.isEmpty();
                    } else if (part.startsWith("CPU_MODEL:")) {
                        cpuModel = part.substring(10).trim();
                    } else if (part.startsWith("ARCH:")) {
                        osArch = part.substring(5).trim();
                    } else if (part.startsWith("VER:")) {
                        agentVer = part.substring(4).trim();
                    } else if (part.startsWith("INSTALL:")) {
                        String ip = part.substring(8).replace("%", "").trim();
                        try {
                            installPct = Double.parseDouble(ip);
                        } catch (Exception ignored) {}
                    }
                }
                workerRegistry.updateTelemetry(workerId, temp, cpu, ram);
                if (os != null || blenderVer != null || installPct >= 0 || gpu != null) {
                    workerRegistry.updateEnvironment(workerId, os, blenderInstalled, blenderVer, installPct, gpu);
                }
                if (cpuModel != null) worker.setCpuModel(cpuModel);
                if (osArch != null) worker.setOsArch(osArch);
                if (agentVer != null) worker.setAgentVersion(agentVer);
                if (worker.getStatus() == WorkerStatus.OFFLINE || worker.getStatus() == null) {
                    workerRegistry.updateStatus(workerId, WorkerStatus.IDLE);
                }
            } catch (Exception ignored) {}
        } else if ("EVICTED".equals(raw)) {
            workerRegistry.handleWorkerFailure(workerId, jobManager);
            workerRegistry.updateStatus(workerId, WorkerStatus.EVICTED);
        }
    }

    /**
     * Gracefully stops the entire Master Node application.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        System.out.println("\n[SHUTDOWN] Initiating Master Node graceful shutdown...");

        if (dashboardServer != null) dashboardServer.stop();
        if (scheduler != null) scheduler.stop();
        if (heartbeatMonitor != null) heartbeatMonitor.stop();
        if (lanDiscovery != null) lanDiscovery.stop();
        if (checkpointManager != null) checkpointManager.stop();
        if (utilizationTracker != null) utilizationTracker.stop();

        try {
            if (agentServerSocket != null && !agentServerSocket.isClosed()) {
                agentServerSocket.close();
            }
        } catch (IOException ignored) {}

        agentThreadPool.shutdownNow();

        // Close all active worker sockets
        for (WorkerState w : workerRegistry.getAllWorkers()) {
            try {
                if (w.getSocket() != null && !w.getSocket().isClosed()) {
                    w.getSocket().close();
                }
            } catch (IOException ignored) {}
        }

        System.out.println("[SHUTDOWN] CampusGrid Master Node terminated cleanly.");
    }

    // Getters for integration verification
    public WorkerRegistry getWorkerRegistry() { return workerRegistry; }
    public JobManager getJobManager() { return jobManager; }
    public ResultCollector getResultCollector() { return resultCollector; }
    public BasicScheduler getScheduler() { return scheduler; }
    public HeartbeatMonitor getHeartbeatMonitor() { return heartbeatMonitor; }
    public DashboardServer getDashboardServer() { return dashboardServer; }
    public StateCheckpointManager getCheckpointManager() { return checkpointManager; }
    public RenderETAEstimator getETAEstimator() { return etaEstimator; }
    public WorkerReliabilityTracker getReliabilityTracker() { return reliabilityTracker; }
    public ClusterUtilizationTracker getUtilizationTracker() { return utilizationTracker; }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        try {
            MasterNodeApplication app = new MasterNodeApplication();
            app.start();
        } catch (Exception e) {
            System.err.println("[FATAL] Failed to start Master Node: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
