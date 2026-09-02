import java.io.*;
import java.net.*;
import java.nio.file.Paths;
import java.util.concurrent.*;
import com.campusgrid.core.*;


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
    private final BenchmarkManager benchmarkManager;
    private final AgentVersionManager versionManager;
    private final MasterDiscoveryBeacon discoveryBeacon;

    private final int agentTcpPort;
    private final int dashboardHttpPort;
    private final int dashboardWsPort;

    private final ExecutorService agentThreadPool = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, Long> lastUpdateTriggerTimes = new ConcurrentHashMap<>();
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
        this.benchmarkManager = new BenchmarkManager(workerRegistry);
        this.versionManager = new AgentVersionManager();
        this.jobManager.setBenchmarkManager(this.benchmarkManager);

        // 2. Initialize Schedulers, Watchdogs, and LAN Discovery Beacon
        this.scheduler = new BasicScheduler(jobManager, workerRegistry, 500);
        this.heartbeatMonitor = new HeartbeatMonitor(workerRegistry, jobManager, 15000, 5000);
        this.discoveryBeacon = new MasterDiscoveryBeacon(agentTcpPort, dashboardHttpPort);

        // 3. Initialize Embedded Web Dashboard Server
        this.dashboardServer = new DashboardServer(jobManager, workerRegistry, benchmarkManager, versionManager, dashboardHttpPort, dashboardWsPort);
    }

    public MasterDiscoveryBeacon getDiscoveryBeacon() {
        return discoveryBeacon;
    }

    public AgentVersionManager getVersionManager() {
        return versionManager;
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

        // 3. Start Heartbeat Watchdog Daemon
        heartbeatMonitor.start();

        // 4. Start UDP LAN Discovery Beacon Daemon
        try {
            discoveryBeacon.start();
        } catch (Exception e) {
            System.err.println("[BOOTSTRAP-WARN] Could not start UDP Discovery Beacon: " + e.getMessage());
        }

        // 5. Start TCP ServerSocket Listener for Agent Nodes
        agentServerSocket = new ServerSocket(agentTcpPort);
        Thread acceptThread = new Thread(this::runAgentAcceptLoop, "Master-Agent-Accept");
        acceptThread.setDaemon(false);
        acceptThread.start();

        // 6. Register JVM Shutdown Hook for Graceful Teardown
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Master-ShutdownHook"));

        System.out.println("[BOOTSTRAP] All 8 Master Node modules (including UDP LAN Auto-Discovery) initialized and running.");
        System.out.println();
    }

    /**
     * Listens for incoming Agent TCP socket connections and sets up streams.
     */
    private void runAgentAcceptLoop() {
        System.out.println("[NETWORK] Listening for Worker Agents on TCP port: " + agentServerSocket.getLocalPort());

        while (running && !agentServerSocket.isClosed()) {
            Socket socket = null;
            try {
                socket = agentServerSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);

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
            } catch (Exception e) {
                System.err.println("[NETWORK-ERR] Error setting up agent connection: " + e.getMessage());
                if (socket != null && !socket.isClosed()) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    /**
     * Continuous packet receiver loop for an individual connected worker agent.
     */
    private void runWorkerReceiverLoop(WorkerState worker, ObjectInputStream inStream) {
        String workerId = worker.getWorkerId();

        try {
            Object obj;
            while (running && worker.getSocket() != null && !worker.getSocket().isClosed() && (obj = inStream.readObject()) != null) {
                worker.setLastHeartbeatTimestamp(System.currentTimeMillis());

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
            try {
                workerRegistry.handleWorkerFailure(workerId, jobManager);
            } catch (Exception e) {
                System.err.printf("[REGISTRY-ERR] Error during worker failure handling for [%s]: %s\n", workerId, e.getMessage());
            }

            try {
                if (inStream != null) inStream.close();
            } catch (Exception ignored) {}
            try {
                if (worker.getOutStream() != null) worker.getOutStream().close();
            } catch (Exception ignored) {}
            try {
                if (worker.getSocket() != null && !worker.getSocket().isClosed()) worker.getSocket().close();
            } catch (Exception ignored) {}
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
                    workerRegistry.updateTelemetry(workerId, hb.getCpuTemperature(), hb.getCpuUsagePercent(), hb.getRamUsagePercent());
                    if (hb.getOsName() != null) worker.setOsName(hb.getOsName());
                    workerRegistry.updateHardwareSpecs(workerId, hb.getCpuModel(), hb.getCpuArch(), hb.getGpuModel(), hb.getGpuComputeType(), hb.isGpuAvailable(), hb.isUseGpu());
                    if (hb.getAgentVersion() != null) worker.setAgentVersion(hb.getAgentVersion());
                    if (hb.getAgentBuildNumber() > 0) worker.setAgentBuildNumber(hb.getAgentBuildNumber());
                    if (hb.getStatus() != null && worker.getStatus() != WorkerStatus.BUSY) {
                        workerRegistry.updateStatus(workerId, hb.getStatus());
                    }
                    checkAndTriggerAgentUpdate(worker, hb.getAgentVersion(), hb.getAgentBuildNumber());
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
            boolean success = "SUCCESS".equalsIgnoreCase(status) || "CANCELLED".equalsIgnoreCase(status);

            // Dynamically query zip frames via reflection
            byte[] zippedData = null;
            try {
                zippedData = (byte[]) clazz.getMethod("getZippedFramesData").invoke(resultObj);
            } catch (Exception ignored) {}

            // Unzip the zippedData to `./output/<jobId>/`
            if (zippedData != null && zippedData.length > 0) {
                unzipFrames(jobId, zippedData);
            }

            String taskId = null;
            try {
                taskId = (String) clazz.getMethod("getTaskId").invoke(resultObj);
            } catch (Exception ignored) {}
            if (taskId == null || taskId.isEmpty()) {
                taskId = worker.getCurrentTaskId() != null ? worker.getCurrentTaskId() : (jobId + "_T001");
            }

            System.out.printf("[RECEIVER] RenderResult for Job [%s] Task [%s] from [%s]: %s (Extracted frame payload)\n",
                jobId, taskId, workerId, status);

            TaskResultPayload resultPayload = new TaskResultPayload(
                jobId, taskId, success, new byte[0], success ? null : "Render status: " + status
            );
            resultCollector.handleTaskResult(workerId, resultPayload);
        } catch (Exception e) {
            System.err.println("[RECEIVER-ERR] Error unpacking RenderResult: " + e.getMessage());
        }
    }

    private void unzipFrames(String jobId, byte[] zippedData) {
        if (zippedData == null || zippedData.length == 0) return;
        java.io.File outputDir = new java.io.File("./output/" + jobId);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zippedData))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String cleanName = new java.io.File(entry.getName()).getName();
                if (cleanName.isEmpty()) continue;
                java.io.File destFile = new java.io.File(outputDir, cleanName);
                // Zip slip protection
                if (!destFile.getCanonicalPath().startsWith(outputDir.getCanonicalPath())) {
                    throw new SecurityException("Zip slip detected: " + entry.getName());
                }
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                zis.closeEntry();
            }
            System.out.printf("[RECEIVER] ✓ Unzipped received frames to: %s\n", outputDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[RECEIVER-ERR] Failed unzipping frames: " + e.getMessage());
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

            // CRITICAL: SoftwareEngine-1.0 fallback is NOT native Blender
            boolean isRealBlender = (blenderVer != null 
                && !blenderVer.equalsIgnoreCase("Unknown") 
                && !blenderVer.toLowerCase().contains("softwareengine")
                && !blenderVer.isEmpty());
            
            if (isRealBlender) {
                workerRegistry.updateEnvironment(workerId, null, true, blenderVer, -1.0, null);
            }

            worker.setCurrentRenderFrame(currentFrame);
            worker.setTotalRenderFrames(totalFrames);
            worker.setCurrentRenderProgress(pct);

            // Update the SubTask progress so the Job overall progress is frame-accurate
            String taskId = worker.getCurrentTaskId();
            if (jobId != null) {
                Job job = jobManager.getJob(jobId);
                if (job != null) {
                    for (Job.SubTask st : job.getSubTasks()) {
                        boolean matches = (taskId != null && taskId.equals(st.getTaskId()))
                            || (taskId == null 
                                && st.getStatus() == Job.SubTaskStatus.DISPATCHED
                                && worker.getWorkerId().equals(st.getAssignedWorkerId()));
                        if (matches) {
                            st.setProgressPercentage(pct);
                            break;
                        }
                    }
                }
            }

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
        if (raw.startsWith("HEARTBEAT")) {
            worker.setLastHeartbeatTimestamp(System.currentTimeMillis());
            try {
                int temp = 36;
                double cpu = 0.0;
                double ram = 50.0;
                String os = null;
                String cpuModel = null;
                String cpuArch = null;
                String gpuModel = null;
                String gpuCompute = null;
                Boolean gpuAvail = null;
                Boolean useGpu = null;
                String blenderVer = null;
                boolean blenderInstalled = false;
                double installPct = -1.0;
                double renderPct = -1.0;
                String installMsg = null;

                String agentVer = null;
                int agentBuild = 0;
                String[] parts = raw.split("\\|");
                for (String part : parts) {
                    part = part.trim();
                    if (part.startsWith("AGENT_VERSION:")) {
                        agentVer = part.substring(14).trim();
                    } else if (part.startsWith("AGENT_BUILD:")) {
                        try { agentBuild = Integer.parseInt(part.substring(12).trim()); } catch (Exception ignored) {}
                    } else if (part.startsWith("TEMP:")) {
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
                    } else if (part.startsWith("CPU_MODEL:")) {
                        cpuModel = part.substring(10).trim();
                    } else if (part.startsWith("ARCH:")) {
                        cpuArch = part.substring(5).trim();
                    } else if (part.startsWith("GPU:")) {
                        gpuModel = part.substring(4).trim();
                    } else if (part.startsWith("GPUTYPE:")) {
                        gpuCompute = part.substring(8).trim();
                    } else if (part.startsWith("GPU_AVAIL:")) {
                        gpuAvail = Boolean.parseBoolean(part.substring(10).trim());
                    } else if (part.startsWith("USEGPU:")) {
                        useGpu = Boolean.parseBoolean(part.substring(7).trim());
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
                    } else if (part.startsWith("PROGRESS:")) {
                        String pr = part.substring(9).replace("%", "").trim();
                        try {
                            renderPct = Double.parseDouble(pr);
                        } catch (Exception ignored) {}
                    } else if (part.startsWith("MSG:")) {
                        installMsg = part.substring(4).trim();
                    }
                }
                workerRegistry.updateTelemetry(workerId, temp, cpu, ram);
                if (agentVer != null) worker.setAgentVersion(agentVer);
                if (agentBuild > 0) worker.setAgentBuildNumber(agentBuild);
                if (os != null || blenderVer != null || installPct >= 0 || installMsg != null) {
                    workerRegistry.updateEnvironment(workerId, os, blenderInstalled, blenderVer, installPct, installMsg);
                }
                if (cpuModel != null || cpuArch != null || gpuModel != null || gpuCompute != null || gpuAvail != null || useGpu != null) {
                    workerRegistry.updateHardwareSpecs(workerId, cpuModel, cpuArch, gpuModel, gpuCompute, gpuAvail, useGpu);
                }
                if (agentVer != null || agentBuild > 0) {
                    checkAndTriggerAgentUpdate(worker, agentVer, agentBuild);
                }

                String currentJobId = worker.getCurrentJobId();
                String currentTaskId = worker.getCurrentTaskId();
                if (renderPct >= 0 && currentJobId != null) {
                    Job job = jobManager.getJob(currentJobId);
                    if (job != null) {
                        for (Job.SubTask st : job.getSubTasks()) {
                            boolean match = (currentTaskId != null && currentTaskId.equals(st.getTaskId()))
                                || (currentTaskId == null
                                    && st.getStatus() == Job.SubTaskStatus.DISPATCHED
                                    && worker.getWorkerId().equals(st.getAssignedWorkerId()));
                            if (match) {
                                st.setProgressPercentage(renderPct);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        } else if ("EVICTED".equals(raw)) {
            workerRegistry.handleWorkerFailure(workerId, jobManager);
            workerRegistry.updateStatus(workerId, WorkerStatus.EVICTED);
        }
    }

    private void checkAndTriggerAgentUpdate(WorkerState worker, String agentVer, int agentBuild) {
        if (versionManager.isAgentOutdated(agentVer, agentBuild)) {
            String workerId = worker.getWorkerId();
            String ip = worker.getIpAddress();
            long now = System.currentTimeMillis();
            Long last = (ip != null) ? lastUpdateTriggerTimes.get(ip) : null;
            if (last != null && (now - last) < 45000) {
                // Throttle updates to once every 45s per host to prevent restart loops
                return;
            }
            if (ip != null) {
                lastUpdateTriggerTimes.put(ip, now);
            }

            System.out.printf("[VERSION-SYNC] ⚠ Outdated Agent detected on [%s] (v%s-b%d < Master v%s-b%d). Dispatched UPDATE_AGENT directive.\n",
                workerId, agentVer, agentBuild, versionManager.getCurrentVersion(), versionManager.getCurrentBuild());
            try {
                ObjectOutputStream out = worker.getOutStream();
                if (out != null) {
                    synchronized (out) {
                        out.writeObject(new GridMessage(MessageType.UPDATE_AGENT, "MASTER", "/download/agent.jar"));
                        out.writeObject("UPDATE_AGENT: /download/agent.jar | VERSION: " + versionManager.getCurrentVersion() + " | BUILD: " + versionManager.getCurrentBuild());
                        out.flush();
                        out.reset();
                    }
                }
            } catch (Exception e) {
                System.err.printf("[VERSION-SYNC-ERR] Failed dispatching update to worker %s: %s\n", workerId, e.getMessage());
            }
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
        if (discoveryBeacon != null) discoveryBeacon.stop();

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
