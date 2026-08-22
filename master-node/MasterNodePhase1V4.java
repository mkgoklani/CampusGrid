import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CAMPUS GRID - MASTER NODE CONTROL PLANE - PHASE 1 VERSION 4
 * 
 * GENERIC TILE & FRAME WORKLOAD ORCHESTRATOR
 * 
 * Upgrades the Phase 4 Orchestrator to support generic, type-agnostic parallel workloads:
 * 1. Generic Task & Chunk Container (TaskChunk): Encapsulates taskId, sequenceIndex,
 *    totalChunks, chunkType (IMAGE_TILE / ANIMATION_FRAME), and generic binary payload.
 * 2. Dynamic Workload Generators: Dynamic creation of row/tile image bands or animation frame
 *    task sequences dispatched straight to the pending queue.
 * 3. Out-of-Order Sequence Aggregator: Stores completed bytes in a thread-safe ConcurrentHashMap
 *    keyed by sequenceIndex as they return, tracking completion count atomically.
 * 4. Continuous Streaming Pipeline: Workers immediately retrieve new task chunks upon completing
 *    previous tasks, without blocking or waiting for other batch components to finish.
 * 5. High-reliability mechanisms preserved: Timeout watchdogs (15s), thermal load balancing
 *    routing to coolest available agent, and fail-fast re-queue on crash.
 * 
 * Compile and run (requires BypassSandbox: true for local TCP loopback socket testing):
 *   javac MasterNodePhase1V4.java && java MasterNodePhase1V4
 * 
 * @author Campus Grid Engineering Team
 * @version 4.1
 */
public class MasterNodePhase1V4 {

    // ============================================================================
    // GLOBAL STRUCTURES & CONFIGURATION
    // ============================================================================

    private static final int PORT = 8080;
    private static final int DISPATCH_TIMEOUT_SECONDS = 15;
    private static volatile boolean isServerRunning = true;
    private static volatile boolean abortInitiated = false;
    private static final Object abortLock = new Object();

    /**
     * Phase 4.B Registry: Maps unique client identifiers (IP:Port) to their
     * real-time state tracking objects (AgentState).
     */
    private static final ConcurrentHashMap<String, AgentState> connectionRegistry = 
        new ConcurrentHashMap<>();

    /**
     * Fixed thread pool for managing background network I/O loops and simulator clients.
     */
    private static final ExecutorService connectionThreadPool = 
        Executors.newFixedThreadPool(15);

    /**
     * Scheduled executor for timeout watchdog tasks (Phase 4.A).
     */
    private static final ScheduledExecutorService timeoutScheduler = 
        Executors.newScheduledThreadPool(2);

    private static ServerSocket masterServerSocket;

    // ============================================================================
    // WORKLOAD MANAGEMENT & AGGREGATION (PHASE 4.C/4.D RESETTABLE)
    // ============================================================================

    /**
     * Thread-safe queue containing generic TaskChunk descriptors waiting to be routed.
     */
    private static final ConcurrentLinkedQueue<TaskChunk> pendingTaskQueue = 
        new ConcurrentLinkedQueue<>();

    /**
     * Out-of-Order Sequence Aggregator (Phase 4.D requirement 3):
     * ConcurrentHashMap mapping sequenceIndex (Integer) to the returned binary result (byte[]).
     */
    private static final ConcurrentHashMap<Integer, byte[]> indexedResults = 
        new ConcurrentHashMap<>();

    /**
     * Synchronization barrier. Re-initialized at start of each cycle.
     */
    private static volatile CountDownLatch scatterGatherBarrier;

    // ============================================================================
    // SERIALIZABLE COMMUNICATIONS MODEL
    // ============================================================================

    /**
     * Generic Task Chunk holding metadata and generic payload (Phase 4.D requirement 1).
     */
    public static class TaskChunk implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String taskId;
        private final int sequenceIndex;
        private final int totalChunks;
        private final String chunkType; // e.g., "IMAGE_TILE" or "ANIMATION_FRAME"
        private final byte[] payload;   // Generic binary wrapper

        public TaskChunk(String taskId, int sequenceIndex, int totalChunks, String chunkType, byte[] payload) {
            this.taskId = taskId;
            this.sequenceIndex = sequenceIndex;
            this.totalChunks = totalChunks;
            this.chunkType = chunkType;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return String.format("TaskChunk[ID=%s, Seq=%d/%d, Type=%s, PayloadSize=%d bytes]", 
                taskId, sequenceIndex, totalChunks, chunkType, (payload != null ? payload.length : 0));
        }
    }

    /**
     * Packet sent from Master to Agent.
     */
    public static class TaskPacket implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TaskChunk chunk;
        private final String command; // E.g., "PROCESS" or "KILL"

        public TaskPacket(TaskChunk chunk, String command) {
            this.chunk = chunk;
            this.command = command;
        }
    }

    /**
     * Packet sent from Agent back to Master (supports both telemetry and results).
     */
    public static class ResponsePacket implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String packetType; // "TELEMETRY" or "RESULT"
        private final int cpuTemperature;
        private final String taskId;
        private final int sequenceIndex;
        private final byte[] resultData;

        // Constructor for Telemetry Packets
        public ResponsePacket(int cpuTemperature) {
            this.packetType = "TELEMETRY";
            this.cpuTemperature = cpuTemperature;
            this.taskId = null;
            this.sequenceIndex = -1;
            this.resultData = null;
        }

        // Constructor for Result Packets
        public ResponsePacket(String taskId, int sequenceIndex, byte[] resultData) {
            this.packetType = "RESULT";
            this.cpuTemperature = -1;
            this.taskId = taskId;
            this.sequenceIndex = sequenceIndex;
            this.resultData = resultData;
        }
    }

    // ============================================================================
    // AGENT STATE REPRESENTATION (PHASE 4.B)
    // ============================================================================

    private static class AgentState {
        private final String ipAddress;
        private final Socket socket;
        private final ObjectInputStream input;
        private final ObjectOutputStream output;

        private volatile int lastKnownCpuTemp = 40;
        private volatile boolean isBusy = false;
        private volatile TaskChunk currentTask = null;
        private volatile CompletableFuture<byte[]> currentTaskFuture = null;

        public AgentState(String ipAddress, Socket socket) throws IOException {
            this.ipAddress = ipAddress;
            this.socket = socket;
            // Write and flush header immediately to prevent ObjectInputStream deadlocks
            this.output = new ObjectOutputStream(socket.getOutputStream());
            this.output.flush();
            this.input = new ObjectInputStream(socket.getInputStream());
        }

        public synchronized void send(Object obj) throws IOException {
            if (!socket.isClosed()) {
                output.writeObject(obj);
                output.flush();
                output.reset(); // Clear object serialization cache to save memory
            }
        }
    }

    // ============================================================================
    // MAIN CONTROL PLANE ENTRY POINT
    // ============================================================================

    public static void main(String[] args) {
        try {
            masterServerSocket = new ServerSocket(PORT);
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  CAMPUS GRID - GENERIC WORKLOAD ORCHESTRATOR - PHASE 1 V4  ║");
            System.out.println("║  Listening on port: " + PORT + "                                   ║");
            System.out.println("║  Workload Agnostic Serialization: Object stream            ║");
            System.out.println("║  Thermal Load Balancing: ENABLED                          ║");
            System.out.println("║  Continuous Streaming Pipeline: ACTIVE                     ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // 1. Start Telemetry CLI interface thread
            Thread telemetryDaemon = new Thread(MasterNodePhase1V4::startTelemetryInterface);
            telemetryDaemon.setDaemon(true);
            telemetryDaemon.setName("Telemetry-CLI");
            telemetryDaemon.start();

            // 2. Start Network Accept Loop thread
            Thread acceptLoopThread = new Thread(MasterNodePhase1V4::runAcceptLoop);
            acceptLoopThread.setDaemon(false);
            acceptLoopThread.setName("Accept-Loop");
            acceptLoopThread.start();

            // 3. Start Central Routing & Dispatcher thread
            Thread dispatchThread = new Thread(MasterNodePhase1V4::runOrchestratorDispatch);
            dispatchThread.setDaemon(true);
            dispatchThread.setName("Orchestrator-Dispatch");
            dispatchThread.start();

            // 4. Start Local Agent Simulator (N=3 workers)
            System.out.println("[SIMULATOR] Launching simulated agent nodes on localhost...");
            startSimulatedAgents();
            Thread.sleep(2000); // Wait for agents to handshake and send telemetry

            // 5. Run Continuous Multicycle Workloads
            runOrchestratorCycles();

            // Block main thread to keep diagnostic console open
            acceptLoopThread.join();

        } catch (IOException e) {
            System.err.println("[FATAL] Failed to initialize Master ServerSocket: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("[MAIN] Orchestrator interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            isServerRunning = false;
            shutdownMasterNode();
        }
    }

    // ============================================================================
    // PHASE 4.C/4.D: WORKLOAD GENERATORS & CYCLE RUNNER
    // ============================================================================

    /**
     * Enqueues 'numSlices' image tile segments to the dispatch queue (Phase 4.D requirement 2).
     */
    public static void enqueueImageSlices(String taskId, int numSlices) {
        System.out.println("[GENERATOR] Enqueuing " + numSlices + " Row/Tile slices for Task: " + taskId);
        for (int i = 0; i < numSlices; i++) {
            byte[] mockPayload = ("IMAGE_TILE_CONFIG_SLICE_" + i).getBytes();
            pendingTaskQueue.add(new TaskChunk(taskId, i, numSlices, "IMAGE_TILE", mockPayload));
        }
    }

    /**
     * Enqueues sequence frame descriptors for render queue distribution (Phase 4.D requirement 2).
     */
    public static void enqueueAnimationSequence(String taskId, int startFrame, int endFrame) {
        int totalFrames = endFrame - startFrame + 1;
        System.out.println("[GENERATOR] Enqueuing Animation frames " + startFrame + " to " + endFrame + " for Task: " + taskId);
        for (int frame = startFrame; frame <= endFrame; frame++) {
            byte[] mockPayload = ("ANIMATION_FRAME_CONFIG_FRAME_" + frame).getBytes();
            pendingTaskQueue.add(new TaskChunk(taskId, frame, totalFrames, "ANIMATION_FRAME", mockPayload));
        }
    }

    /**
     * Coordinates the continuous multi-cycle scatter-gather test pipeline.
     */
    private static void runOrchestratorCycles() throws InterruptedException {
        // Cycle 1: Generic static image tile slice render
        System.out.println();
        System.out.println("======================================================================");
        System.out.println("▶ CYCLE 1: STATIC IMAGE TILE RENDERING");
        System.out.println("======================================================================");
        
        indexedResults.clear();
        scatterGatherBarrier = new CountDownLatch(5);
        enqueueImageSlices("Task_Image_HD", 5);
        
        long start1 = System.currentTimeMillis();
        scatterGatherBarrier.await(); // Main thread blocks waiting for all 5 chunks
        long elapsed1 = System.currentTimeMillis() - start1;

        System.out.println("----------------------------------------------------------------------");
        System.out.println("✓ CYCLE 1 COMPLETED IN " + elapsed1 + "ms");
        System.out.println("[AGGREGATION] Results collected in out-of-order map:");
        for (Map.Entry<Integer, byte[]> entry : indexedResults.entrySet()) {
            System.out.println("  Slice #" + entry.getKey() + " -> " + new String(entry.getValue()));
        }
        System.out.println("======================================================================");

        Thread.sleep(4000);

        // Cycle 2: Multi-frame animation rendering sequence
        System.out.println();
        System.out.println("======================================================================");
        System.out.println("▶ CYCLE 2: MULTI-FRAME ANIMATION RENDERING");
        System.out.println("======================================================================");
        
        indexedResults.clear();
        scatterGatherBarrier = new CountDownLatch(5);
        enqueueAnimationSequence("Task_Logo_Intro", 101, 105);
        
        long start2 = System.currentTimeMillis();
        scatterGatherBarrier.await();
        long elapsed2 = System.currentTimeMillis() - start2;

        System.out.println("----------------------------------------------------------------------");
        System.out.println("✓ CYCLE 2 COMPLETED IN " + elapsed2 + "ms");
        System.out.println("[AGGREGATION] Results collected in out-of-order map:");
        for (Map.Entry<Integer, byte[]> entry : indexedResults.entrySet()) {
            System.out.println("  Frame #" + entry.getKey() + " -> " + new String(entry.getValue()));
        }
        System.out.println("======================================================================");

        Thread.sleep(4000);

        // Cycle 3: Pure verification batch
        System.out.println();
        System.out.println("======================================================================");
        System.out.println("▶ CYCLE 3: VERIFICATION RUN");
        System.out.println("======================================================================");
        
        indexedResults.clear();
        scatterGatherBarrier = new CountDownLatch(5);
        enqueueImageSlices("Task_Final_Verify", 5);
        
        long start3 = System.currentTimeMillis();
        scatterGatherBarrier.await();
        long elapsed3 = System.currentTimeMillis() - start3;

        System.out.println("----------------------------------------------------------------------");
        System.out.println("✓ CYCLE 3 COMPLETED IN " + elapsed3 + "ms");
        System.out.println("[AGGREGATION] Final results in out-of-order map:");
        for (Map.Entry<Integer, byte[]> entry : indexedResults.entrySet()) {
            System.out.println("  Index #" + entry.getKey() + " -> " + new String(entry.getValue()));
        }
        System.out.println("======================================================================");
        System.out.println();
        System.out.println("[MAIN] Demonstration complete. System idle. Type 'STATUS' to view.");
    }

    // ============================================================================
    // NETWORK ACCEPT LOOP & RECEIVER
    // ============================================================================

    private static void runAcceptLoop() {
        while (isServerRunning && !abortInitiated) {
            try {
                Socket incomingConnection = masterServerSocket.accept();
                String clientIP = incomingConnection.getInetAddress().getHostAddress() + ":" + incomingConnection.getPort();

                System.out.println("[REGISTRY] New Agent handshaking: " + clientIP);

                AgentState agentState = new AgentState(clientIP, incomingConnection);
                connectionRegistry.put(clientIP, agentState);

                // Run network receiver on pool thread
                connectionThreadPool.submit(() -> runAgentReceiver(agentState));

            } catch (SocketException e) {
                if (isServerRunning && !abortInitiated) {
                    System.err.println("[ERROR] SocketException in accept loop: " + e.getMessage());
                }
            } catch (IOException e) {
                if (isServerRunning && !abortInitiated) {
                    System.err.println("[ERROR] IOException in accept loop: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Monitors TCP stream payload objects. Decodes ResponsePacket objects asynchronously.
     */
    private static void runAgentReceiver(AgentState agentState) {
        String clientIP = agentState.ipAddress;
        try {
            Object obj;
            while (isServerRunning && !abortInitiated && (obj = agentState.input.readObject()) != null) {
                if (obj instanceof ResponsePacket) {
                    ResponsePacket response = (ResponsePacket) obj;

                    if ("TELEMETRY".equals(response.packetType)) {
                        agentState.lastKnownCpuTemp = response.cpuTemperature;
                    } else if ("RESULT".equals(response.packetType)) {
                        CompletableFuture<byte[]> future = agentState.currentTaskFuture;
                        TaskChunk activeChunk = agentState.currentTask;

                        // Verify packet matches active chunk to prevent stale callbacks
                        if (future != null && activeChunk != null 
                            && response.taskId.equals(activeChunk.taskId) 
                            && response.sequenceIndex == activeChunk.sequenceIndex) {
                            
                            future.complete(response.resultData);
                        }
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            // Socket severed
        } finally {
            cleanupAgent(agentState);
        }
    }

    private static void cleanupAgent(AgentState agentState) {
        String clientIP = agentState.ipAddress;
        try {
            if (!agentState.socket.isClosed()) {
                agentState.socket.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        connectionRegistry.remove(clientIP);
        System.out.println("[REGISTRY] Severed connection: " + clientIP);

        // Fail-Fast Task Re-queue
        CompletableFuture<byte[]> activeFuture = agentState.currentTaskFuture;
        if (activeFuture != null && !activeFuture.isDone()) {
            activeFuture.completeExceptionally(new IOException("Agent connection terminated mid-process"));
        }
    }

    // ============================================================================
    // CENTRAL ROUTING ENGINE: THERMAL LOAD BALANCING (PHASE 4.B)
    // ============================================================================

    private static AgentState getBestIdleAgent() {
        AgentState coolestAgent = null;
        int lowestTemperature = Integer.MAX_VALUE;

        for (AgentState agent : connectionRegistry.values()) {
            if (!agent.isBusy && !agent.socket.isClosed()) {
                if (agent.lastKnownCpuTemp < lowestTemperature) {
                    lowestTemperature = agent.lastKnownCpuTemp;
                    coolestAgent = agent;
                }
            }
        }
        return coolestAgent;
    }

    /**
     * Dispatcher thread continuously checks queue and routes work using Thermal Load Balancing.
     */
    private static void runOrchestratorDispatch() {
        while (isServerRunning && !abortInitiated) {
            try {
                // Peek task chunk
                TaskChunk chunk = pendingTaskQueue.peek();
                if (chunk == null) {
                    Thread.sleep(100);
                    continue;
                }

                // Load balancer selection
                AgentState targetAgent = getBestIdleAgent();
                if (targetAgent == null) {
                    Thread.sleep(200); // Back off if all workers busy
                    continue;
                }

                // Coordinate reservation atomically
                synchronized (targetAgent) {
                    if (targetAgent.isBusy || targetAgent.socket.isClosed()) {
                        continue;
                    }

                    // Atomic queue extraction to prevent duplicate assignments
                    if (pendingTaskQueue.remove(chunk)) {
                        targetAgent.isBusy = true;
                        targetAgent.currentTask = chunk;
                        targetAgent.currentTaskFuture = new CompletableFuture<>();

                        final String agentIP = targetAgent.ipAddress;
                        final CompletableFuture<byte[]> taskFuture = targetAgent.currentTaskFuture;

                        System.out.println("[ORCHESTRATOR] Routing " + chunk + " to Agent [" 
                                           + agentIP + "] (Thermal Selection: Temp = " + targetAgent.lastKnownCpuTemp + "°C)");

                        // Send task command packet
                        try {
                            targetAgent.send(new TaskPacket(chunk, "PROCESS"));
                        } catch (IOException e) {
                            taskFuture.completeExceptionally(e);
                            continue;
                        }

                        // Schedule Watchdog (Phase 4.A - 15s limit)
                        ScheduledFuture<?> watchdog = timeoutScheduler.schedule(() -> {
                            if (!taskFuture.isDone()) {
                                taskFuture.completeExceptionally(
                                    new TimeoutException("Agent rendering execution timed out (limit: " + DISPATCH_TIMEOUT_SECONDS + "s)")
                                );
                            }
                        }, DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                        // Attach non-blocking callbacks
                        taskFuture.whenComplete((resultData, ex) -> {
                            watchdog.cancel(true); // Cancel timer

                            if (ex != null) {
                                // Failure recovery (Timeout / Crash)
                                System.out.println("[ORCHESTRATOR-WARN] ⚠ Execution failed/timed out for " + chunk + " on Agent [" + agentIP + "]!");
                                System.out.println("[ORCHESTRATOR-WARN] Reason: " + ex.getMessage());
                                System.out.println("[ORCHESTRATOR-WARN] Re-queuing chunk and revoking Agent [" + agentIP + "]");

                                pendingTaskQueue.add(chunk); // Re-queue task chunk
                                revokeAgent(agentIP);
                            } else {
                                // Out-of-Order Sequence Aggregation (Phase 4.D requirement 3)
                                indexedResults.put(chunk.sequenceIndex, resultData);
                                scatterGatherBarrier.countDown();

                                // Free agent immediately to pull next task (Continuous streaming requirement 4)
                                freeAgent(agentIP);
                            }
                        });
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[ORCHESTRATOR-ERR] Dispatch exception: " + e.getMessage());
            }
        }
    }

    private static void freeAgent(String ipAddress) {
        AgentState agent = connectionRegistry.get(ipAddress);
        if (agent != null) {
            agent.isBusy = false;
            agent.currentTask = null;
            agent.currentTaskFuture = null;
        }
    }

    private static void revokeAgent(String ipAddress) {
        AgentState agent = connectionRegistry.get(ipAddress);
        if (agent != null) {
            try {
                if (!agent.socket.isClosed()) {
                    agent.socket.close(); // Forcing close triggers cleanupAgent()
                }
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    // ============================================================================
    // TELEMETRY CONSOLE DASHBOARD
    // ============================================================================

    private static void startTelemetryInterface() {
        try (Scanner console = new Scanner(System.in)) {
            System.out.println("[TELEMETRY] Console dashboard active. Commands: STATUS, ABORT, EXIT");

            while (isServerRunning && console.hasNextLine()) {
                String cmd = console.nextLine().trim().toUpperCase();
                switch (cmd) {
                    case "STATUS":
                        displayStatusTelemetry();
                        break;
                    case "ABORT":
                        executeGlobalKillSwitch();
                        break;
                    case "EXIT":
                        isServerRunning = false;
                        System.exit(0);
                        break;
                    case "":
                        break;
                    default:
                        System.out.println("[TELEMETRY] Unknown command: '" + cmd + "'. Commands: STATUS, ABORT, EXIT");
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private static void displayStatusTelemetry() {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  SYSTEM STATUS TELEMETRY                                   ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  Connected Agents: " + String.format("%-40d", connectionRegistry.size()) + "║");
        System.out.println("║  Tasks in Queue: " + String.format("%-42d", pendingTaskQueue.size()) + "║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        if (connectionRegistry.isEmpty()) {
            System.out.println("║  No agents currently registered.                           ║");
        } else {
            int idx = 1;
            for (AgentState agent : connectionRegistry.values()) {
                String taskStr = "None";
                if (agent.currentTask != null) {
                    taskStr = agent.currentTask.chunkType + "#" + agent.currentTask.sequenceIndex;
                }
                String line = String.format("[%d] IP: %-18s Temp: %-3d°C Busy: %-5b Task: %-10s", 
                                            idx++, agent.ipAddress, agent.lastKnownCpuTemp, agent.isBusy, taskStr);
                System.out.println("║  " + String.format("%-56s", line) + "║");
            }
        }
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void executeGlobalKillSwitch() {
        synchronized (abortLock) {
            if (abortInitiated) return;
            abortInitiated = true;
            isServerRunning = false;

            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  🛑 EMERGENCY SHUTDOWN: BROADCASTING TERMINATE PACKETS     🛑 ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            timeoutScheduler.shutdownNow();
            connectionThreadPool.shutdownNow();

            for (AgentState agent : connectionRegistry.values()) {
                try {
                    agent.send(new TaskPacket(null, "KILL"));
                    agent.socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            connectionRegistry.clear();
            System.exit(0);
        }
    }

    private static void shutdownMasterNode() {
        isServerRunning = false;
        try {
            if (masterServerSocket != null && !masterServerSocket.isClosed()) {
                masterServerSocket.close();
            }
            timeoutScheduler.shutdownNow();
            connectionThreadPool.shutdownNow();
        } catch (IOException e) {
            // Ignore
        }
    }

    // ============================================================================
    // SELF-CONTAINED LOCAL AGENT SIMULATOR
    // ============================================================================

    private static void startSimulatedAgents() {
        // Agent-Cool: 38°C base, executes all tasks instantly
        spawnSimulatedAgent("Agent-Cool", 38, false, false);

        // Agent-Warm: 48°C base, simulates a hang (takes 20s) on Cycle 1 (IMAGE_TILE) Index 3
        spawnSimulatedAgent("Agent-Warm", 48, true, false);

        // Agent-Hot: 58°C base, simulates a socket crash (closes stream) on Cycle 2 (ANIMATION_FRAME) Index 102
        spawnSimulatedAgent("Agent-Hot", 58, false, true);
    }

    private static void spawnSimulatedAgent(String name, int baseTemp, 
                                           boolean simulateHang, boolean simulateCrash) {
        Thread thread = new Thread(() -> {
            Random rand = new Random();
            boolean running = true;

            while (running && isServerRunning) {
                try (Socket socket = new Socket("127.0.0.1", PORT);
                     ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
                     
                     out.flush();
                     try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
                        System.out.println("  [SIMULATOR-" + name + "] Connected to Master.");

                        // Continuous Telemetry Thread
                        Thread telemetry = new Thread(() -> {
                            try {
                                while (!socket.isClosed() && isServerRunning) {
                                    int temp = baseTemp + rand.nextInt(5) - 2;
                                    synchronized (out) {
                                        out.writeObject(new ResponsePacket(temp));
                                        out.flush();
                                        out.reset();
                                    }
                                    Thread.sleep(2500);
                                }
                            } catch (Exception e) {
                                // Thread exits on socket close
                            }
                        });
                        telemetry.setDaemon(true);
                        telemetry.start();

                        Object obj;
                        while (isServerRunning && (obj = in.readObject()) != null) {
                            if (obj instanceof TaskPacket) {
                                TaskPacket taskPacket = (TaskPacket) obj;
                                if ("KILL".equals(taskPacket.command)) {
                                    System.out.println("  [SIMULATOR-" + name + "] Received Poison Pill KILL command.");
                                    running = false;
                                    break;
                                }

                                TaskChunk chunk = taskPacket.chunk;
                                System.out.println("  [SIMULATOR-" + name + "] Rendering chunk: " + chunk);

                                // Check for simulated execution issues
                                if (simulateHang && chunk.sequenceIndex == 3 && "IMAGE_TILE".equals(chunk.chunkType)) {
                                    System.out.println("  [SIMULATOR-" + name + "] !!! Simulating execution HANG (20s delay) !!!");
                                    Thread.sleep(20000);
                                } else if (simulateCrash && chunk.sequenceIndex == 102 && "ANIMATION_FRAME".equals(chunk.chunkType)) {
                                    System.out.println("  [SIMULATOR-" + name + "] !!! Simulating socket CRASH (disconnecting) !!!");
                                    break; // Disconnect immediately
                                } else {
                                    // Add randomized delays (500-2000ms) to trigger natural out-of-order arrivals
                                    Thread.sleep(500 + rand.nextInt(1500));
                                }

                                if (!socket.isClosed()) {
                                    byte[] resultBytes = ("RENDERED_DATA_SEQ_" + chunk.sequenceIndex + "_BY_" + name).getBytes();
                                    ResponsePacket response = new ResponsePacket(chunk.taskId, chunk.sequenceIndex, resultBytes);
                                    synchronized (out) {
                                        out.writeObject(response);
                                        out.flush();
                                        out.reset();
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (isServerRunning && running) {
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("Simulator-" + name);
        thread.start();
    }
}
