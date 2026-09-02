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

        // 2. Initialize Schedulers, Watchdogs, and LAN Discovery
        this.scheduler = new BasicScheduler(jobManager, workerRegistry, 500);
        this.heartbeatMonitor = new HeartbeatMonitor(workerRegistry, jobManager, 15000, 5000);
        this.lanDiscovery = new LanDiscoveryResponder(agentTcpPort);

        // 3. Initialize Embedded Web Dashboard Server
        this.dashboardServer = new DashboardServer(jobManager, workerRegistry, dashboardHttpPort, dashboardWsPort);
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

        // 1. Start Dashboard REST & WebSocket Services
        dashboardServer.start();

        // 2. Start Non-Blocking Scheduler Daemon
        scheduler.start();

        // 3. Start Heartbeat Watchdog Daemon & LAN Discovery Responder
        heartbeatMonitor.start();
        lanDiscovery.start();

        // 4. Start TCP ServerSocket Listener for Agent Nodes
        agentServerSocket = new ServerSocket(agentTcpPort);
        Thread acceptThread = new Thread(this::runAgentAcceptLoop, "Master-Agent-Accept");
        acceptThread.setDaemon(false);
        acceptThread.start();

        // 5. Register JVM Shutdown Hook for Graceful Teardown
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Master-ShutdownHook"));

        System.out.println("[BOOTSTRAP] All Master Node modules & LAN Discovery initialized and running successfully.");
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
        } catch (EOFException | SocketException e) {
            // Normal client disconnect or socket closed
            System.out.println("[NETWORK] Worker [" + workerId + "] disconnected.");
        } catch (Exception e) {
            System.err.printf("[NETWORK-ERR] Stream exception on Worker [%s]: %s\n", workerId, e.getMessage());
        } finally {
            // Fault tolerance: Trigger cleanup and auto-requeue of active tasks
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
            System.out.printf("[RECEIVER] RenderResult for Job [%s] Task [%s] from [%s]: %s (%d frame binaries, %dms)\n",
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
                workerRegistry.updateStatus(workerId, WorkerStatus.IDLE);
            }
        } catch (Exception ignored) {}
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
