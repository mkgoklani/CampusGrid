import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import com.campusgrid.core.GridTask;
import com.campusgrid.core.MandelbrotTask;
import com.campusgrid.core.MatrixMultiplicationTask;

/**
 * CAMPUS GRID - MASTER NODE CONTROL PLANE - PHASE 1 VERSION 5
 * 
 * PERFORMANCE BENCHMARK ORCHESTRATOR
 * 
 * Supports two massive distributed workloads to showcase Grid performance speedups:
 * 1. Heavy 8K Mandelbrot set (7,680 x 4,320 binned down to 80x40 ASCII preview).
 * 2. Massive Matrix Multiplication (4,000 x 4,000 deterministic double matrices).
 * 
 * Slices tasks out-of-order, balances workload by CPU temperature, detects worker timeouts,
 * and stitches results back together cleanly.
 * 
 * Compile and run:
 *   javac MasterNodePhase1V5.java && java -cp "out:master-node" MasterNodePhase1V5
 */
public class MasterNodePhase1V5 {

    private static final int PORT = 8080;
    private static final int DISPATCH_TIMEOUT_SECONDS = 45;
    private static volatile boolean isServerRunning = true;
    private static volatile boolean abortInitiated = false;
    private static final Object abortLock = new Object();

    // Workload Trigger Flags
    private static volatile boolean triggerCompute = false;
    private static volatile int selectedWorkload = 0; // 1 = Mandelbrot 8K, 2 = Matrix

    /**
     * Map tracking connected agents, keyed by "IP:Port".
     */
    private static final ConcurrentHashMap<String, AgentState> connectionRegistry = 
        new ConcurrentHashMap<>();

    private static final ExecutorService connectionThreadPool = 
        Executors.newFixedThreadPool(15);

    private static final ScheduledExecutorService timeoutScheduler = 
        Executors.newScheduledThreadPool(2);

    private static ServerSocket masterServerSocket;

    // ============================================================================
    // WORKLOAD & BARRIER STRUCTURES
    // ============================================================================

    private static final ConcurrentLinkedQueue<TaskItem> pendingTaskQueue = 
        new ConcurrentLinkedQueue<>();

    private static final ConcurrentHashMap<Integer, Object> resultsMap = 
        new ConcurrentHashMap<>();

    private static volatile CountDownLatch scatterGatherBarrier;

    /**
     * Wrapper for sub-tasks to preserve their sequence indices and type.
     */
    private static class TaskItem {
        private final Object task;
        private final int sequenceIndex;
        private final String taskType;

        public TaskItem(Object task, int sequenceIndex, String taskType) {
            this.task = task;
            this.sequenceIndex = sequenceIndex;
            this.taskType = taskType;
        }

        @Override
        public String toString() {
            return "TaskItem[Type=" + taskType + ", Seq=" + sequenceIndex + "]";
        }
    }

    // ============================================================================
    // AGENT STATE MODEL
    // ============================================================================

    private static class AgentState {
        private final String ipAddress;
        private final Socket socket;
        private final ObjectInputStream input;
        private final ObjectOutputStream output;

        private volatile int lastKnownCpuTemp = 40;
        private volatile boolean isBusy = false;
        private volatile TaskItem currentTask = null;
        private volatile CompletableFuture<Object> currentTaskFuture = null;

        public AgentState(String ipAddress, Socket socket) throws IOException {
            this.ipAddress = ipAddress;
            this.socket = socket;
            // Write and flush headers first to prevent input stream block deadlocks
            this.output = new ObjectOutputStream(socket.getOutputStream());
            this.output.flush();
            this.input = new ObjectInputStream(socket.getInputStream());
        }

        public synchronized void send(Object obj) throws IOException {
            if (!socket.isClosed()) {
                output.writeObject(obj);
                output.flush();
                output.reset();
            }
        }
    }

    // ============================================================================
    // MAIN ENTRY POINT
    // ============================================================================

    public static void main(String[] args) {
        try {
            masterServerSocket = new ServerSocket(PORT);
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  CAMPUS GRID - PERFORMANCE BENCHMARK ORCHESTRATOR - PH1 V5 ║");
            System.out.println("║  Listening on port: " + PORT + "                                   ║");
            System.out.println("║  Workloads: 8K Mandelbrot & 4,000x4,000 Matrix Mult.       ║");
            System.out.println("║  Telemetry & Load Balancing: ENABLED                       ║");
            System.out.println("║  To run agents: java -cp out com.campusgrid.agent.Agent    ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // 1. Start Telemetry CLI
            Thread telemetryDaemon = new Thread(MasterNodePhase1V5::startTelemetryInterface);
            telemetryDaemon.setDaemon(true);
            telemetryDaemon.setName("Telemetry-CLI");
            telemetryDaemon.start();

            // 2. Start Accept Loop
            Thread acceptLoopThread = new Thread(MasterNodePhase1V5::runAcceptLoop);
            acceptLoopThread.setDaemon(false);
            acceptLoopThread.setName("Accept-Loop");
            acceptLoopThread.start();

            // 3. Start Dispatcher Loop
            Thread dispatchThread = new Thread(MasterNodePhase1V5::runOrchestratorDispatch);
            dispatchThread.setDaemon(true);
            dispatchThread.setName("Orchestrator-Dispatch");
            dispatchThread.start();

            // 4. Run consecutive compute cycles
            runWorkloadOrchestrator();

            // Keep main thread alive
            acceptLoopThread.join();

        } catch (IOException e) {
            System.err.println("[FATAL] Server Socket binding error: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("[MAIN] Master interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            isServerRunning = false;
            shutdownMasterNode();
        }
    }

    // ============================================================================
    // WORKLOAD DISPATCH CONTROL LOOP
    // ============================================================================

    private static void runWorkloadOrchestrator() throws InterruptedException {
        int runId = 1;

        System.out.println("[ORCHESTRATOR] System ready. Connect your Agents, then type 'START' in this console to run a workload!");
        System.out.println();

        while (isServerRunning) {
            // Wait for user to input START
            while (!triggerCompute && isServerRunning) {
                Thread.sleep(200);
            }
            if (!isServerRunning) {
                break;
            }
            triggerCompute = false; // Reset trigger

            int choice = selectedWorkload;
            if (choice == 1) {
                // Mandelbrot 8K execution
                int totalStrips = 8;
                System.out.println();
                System.out.println("======================================================================");
                System.out.println("▶ STARTING HEAVY MANDELBROT 8K CYCLE #" + runId + " (Active Agents: " + connectionRegistry.size() + ")");
                System.out.println("  Specs: 7680 x 4320 Resolution, Tuned to 1,500 iterations max.");
                System.out.println("======================================================================");

                resultsMap.clear();
                scatterGatherBarrier = new CountDownLatch(totalStrips);

                MandelbrotTask originalTask = new MandelbrotTask(
                    -2.0, 1.0, -1.2, 1.2, 7680, 4320, 1500
                );

                List<GridTask<int[][]>> taskStrips = originalTask.split(totalStrips);
                for (int i = 0; i < totalStrips; i++) {
                    pendingTaskQueue.add(new TaskItem(taskStrips.get(i), i, "MANDELBROT"));
                }

                System.out.println("[SCATTER] Split Mandelbrot 8K into " + totalStrips + " strips and enqueued.");
                long startTime = System.currentTimeMillis();
                scatterGatherBarrier.await();
                long elapsed = System.currentTimeMillis() - startTime;

                List<int[][]> orderedResults = new ArrayList<>();
                for (int i = 0; i < totalStrips; i++) {
                    orderedResults.add((int[][]) resultsMap.get(i));
                }

                int[][] merged = originalTask.merge(orderedResults);
                System.out.println("✓ CYCLE #" + runId + " COMPLETED IN " + elapsed + "ms");
                System.out.println();
                System.out.println("[RENDER] Downsampled ASCII preview of 8K Mandelbrot:");
                renderMandelbrotAscii(merged);
                System.out.println();

            } else if (choice == 2) {
                // Matrix Multiplication 2000x2000 execution
                int totalStrips = 5;
                System.out.println();
                System.out.println("======================================================================");
                System.out.println("▶ STARTING MASSIVE MATRIX MULTIPLICATION #" + runId + " (Active Agents: " + connectionRegistry.size() + ")");
                System.out.println("  Specs: deterministic 2,000 x 2,000 double matrix multiplication");
                System.out.println("======================================================================");

                resultsMap.clear();
                scatterGatherBarrier = new CountDownLatch(totalStrips);

                MatrixMultiplicationTask originalTask = new MatrixMultiplicationTask(2000);

                List<GridTask<double[][]>> taskStrips = originalTask.split(totalStrips);
                for (int i = 0; i < totalStrips; i++) {
                    pendingTaskQueue.add(new TaskItem(taskStrips.get(i), i, "MATRIX"));
                }

                System.out.println("[SCATTER] Split Matrix Multiplication (2,000 x 2,000) into 5 strips (400 rows each) and enqueued.");
                long startTime = System.currentTimeMillis();
                scatterGatherBarrier.await();
                long elapsed = System.currentTimeMillis() - startTime;

                List<double[][]> orderedResults = new ArrayList<>();
                for (int i = 0; i < totalStrips; i++) {
                    orderedResults.add((double[][]) resultsMap.get(i));
                }

                double[][] merged = originalTask.merge(orderedResults);
                System.out.println("✓ MATRIX CYCLE #" + runId + " COMPLETED IN " + elapsed + "ms");
                System.out.println();
                System.out.println("[VERIFY] Sample calculation outputs (Stitched C matrix):");
                System.out.printf("  C[0][0]       = %.6f\n", merged[0][0]);
                System.out.printf("  C[100][100] = %.6f\n", merged[100][100]);
                System.out.printf("  C[1000][20] = %.6f\n", merged[1000][20]);
                System.out.printf("  C[1999][1999] = %.6f\n", merged[1999][1999]);
                System.out.println();
            }

            runId++;
            System.out.println("[ORCHESTRATOR] System ready. Connect more Agents, type 'START' to run another cycle, or type 'STATUS' to view registry.");
            System.out.println();
        }
    }

    /**
     * Renders Mandelbrot set to console output using downsampling.
     * Maps 7680x4320 grid to 80x40 characters for terminal display.
     */
    private static void renderMandelbrotAscii(int[][] matrix) {
        int w = matrix.length;
        int h = matrix[0].length;
        int targetW = 80;
        int targetH = 40;
        int stepX = Math.max(1, w / targetW);
        int stepY = Math.max(1, h / targetH);

        System.out.println("╔" + "═".repeat(targetW) + "╗");
        for (int y = 0; y < h; y += stepY) {
            if (y / stepY >= targetH) break;
            System.out.print("║");
            for (int x = 0; x < w; x += stepX) {
                if (x / stepX >= targetW) break;
                int iter = matrix[x][y];
                if (iter == 1500) {
                    System.out.print(" "); // Set interior
                } else if (iter > 300) {
                    System.out.print("#");
                } else if (iter > 60) {
                    System.out.print("*");
                } else if (iter > 12) {
                    System.out.print(".");
                } else {
                    System.out.print(" ");
                }
            }
            System.out.println("║");
        }
        System.out.println("╚" + "═".repeat(targetW) + "╝");
    }

    // ============================================================================
    // NETWORK ACCEPT LOOP & RECEIVER
    // ============================================================================

    private static void runAcceptLoop() {
        while (isServerRunning && !abortInitiated) {
            try {
                Socket incoming = masterServerSocket.accept();
                String clientIP = incoming.getInetAddress().getHostAddress() + ":" + incoming.getPort();

                System.out.println("[REGISTRY] New Agent connection handshaking: " + clientIP);

                AgentState agentState = new AgentState(clientIP, incoming);
                connectionRegistry.put(clientIP, agentState);

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

    private static void runAgentReceiver(AgentState agentState) {
        String clientIP = agentState.ipAddress;
        try {
            Object obj;
            while (isServerRunning && !abortInitiated && (obj = agentState.input.readObject()) != null) {
                if (obj instanceof String) {
                    String msg = (String) obj;
                    if (msg.startsWith("HEARTBEAT | TEMP: ")) {
                        String tempStr = msg.substring(18).trim();
                        if (tempStr.endsWith("°C")) {
                            tempStr = tempStr.substring(0, tempStr.length() - 2);
                        }
                        try {
                            int temp = Integer.parseInt(tempStr);
                            agentState.lastKnownCpuTemp = temp;
                        } catch (NumberFormatException e) {
                            // Ignore
                        }
                    } else if ("EVICTED".equals(msg)) {
                        System.out.println("[ORCHESTRATOR-WARN] ⚠ Agent [" + clientIP + "] reported eviction due to user activity!");
                        CompletableFuture<Object> future = agentState.currentTaskFuture;
                        if (future != null && !future.isDone()) {
                            future.completeExceptionally(new IOException("Agent evicted dynamically"));
                        }
                    }
                } else {
                    // Accepts int[][] or double[][]
                    CompletableFuture<Object> future = agentState.currentTaskFuture;
                    TaskItem activeTask = agentState.currentTask;
                    if (future != null && activeTask != null && !future.isDone()) {
                        future.complete(obj);
                    }
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            // Disconnected
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

        CompletableFuture<Object> activeFuture = agentState.currentTaskFuture;
        if (activeFuture != null && !activeFuture.isDone()) {
            activeFuture.completeExceptionally(new IOException("Agent connection terminated mid-process"));
        }
    }

    // ============================================================================
    // CENTRAL ROUTING ENGINE: THERMAL LOAD BALANCING
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

    private static void runOrchestratorDispatch() {
        while (isServerRunning && !abortInitiated) {
            try {
                TaskItem item = pendingTaskQueue.peek();
                if (item == null) {
                    Thread.sleep(100);
                    continue;
                }

                AgentState targetAgent = getBestIdleAgent();
                if (targetAgent == null) {
                    Thread.sleep(200);
                    continue;
                }

                synchronized (targetAgent) {
                    if (targetAgent.isBusy || targetAgent.socket.isClosed()) {
                        continue;
                    }

                    if (pendingTaskQueue.remove(item)) {
                        targetAgent.isBusy = true;
                        targetAgent.currentTask = item;
                        targetAgent.currentTaskFuture = new CompletableFuture<>();

                        final String agentIP = targetAgent.ipAddress;
                        final CompletableFuture<Object> taskFuture = targetAgent.currentTaskFuture;

                        System.out.println("[ORCHESTRATOR] Routing strip [Seq=" + item.sequenceIndex 
                                           + "] to Agent [" + agentIP + "] (Thermal Temp: " + targetAgent.lastKnownCpuTemp + "°C)");

                        // Send task object directly to Agent PayloadListener
                        try {
                            targetAgent.send(item.task);
                        } catch (IOException e) {
                            taskFuture.completeExceptionally(e);
                            continue;
                        }

                        // Schedule Timeout Watchdog (15 seconds limit)
                        ScheduledFuture<?> watchdog = timeoutScheduler.schedule(() -> {
                            if (!taskFuture.isDone()) {
                                taskFuture.completeExceptionally(
                                    new TimeoutException("Agent execution timed out (limit: " + DISPATCH_TIMEOUT_SECONDS + "s)")
                                );
                            }
                        }, DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                        // Completion Callback handling
                        taskFuture.whenComplete((resultData, ex) -> {
                            watchdog.cancel(true);

                            if (ex != null) {
                                System.out.println("[ORCHESTRATOR-WARN] ⚠ Strip [Seq=" + item.sequenceIndex 
                                                   + "] failed/timed out on Agent [" + agentIP + "]!");
                                System.out.println("[ORCHESTRATOR-WARN] Reason: " + ex.getMessage());
                                System.out.println("[ORCHESTRATOR-WARN] Re-queuing strip and revoking Agent [" + agentIP + "]");

                                pendingTaskQueue.add(item);
                                revokeAgent(agentIP);
                            } else {
                                System.out.println("[ORCHESTRATOR-SUCCESS] Strip [Seq=" + item.sequenceIndex 
                                                   + "] completed by Agent [" + agentIP + "]");
                                
                                resultsMap.put(item.sequenceIndex, resultData);
                                scatterGatherBarrier.countDown();

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
                    agent.socket.close();
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
            System.out.println("[TELEMETRY] Console dashboard active. Commands: START, STATUS, ABORT, EXIT");

            while (isServerRunning && console.hasNextLine()) {
                String cmd = console.nextLine().trim().toUpperCase();
                switch (cmd) {
                    case "START":
                        if (connectionRegistry.isEmpty()) {
                            System.out.println("[TELEMETRY] Cannot start calculation: No agents are registered yet.");
                        } else {
                            System.out.println();
                            System.out.println("[TELEMETRY] Select Workload to Run:");
                            System.out.println("  [1] Heavy 8K Mandelbrot Fractal (7680x4320, 15,000 iterations max)");
                            System.out.println("  [2] Massive Matrix Multiplication (AI-Math 4,000 x 4,000 double matrix)");
                            System.out.print("[TELEMETRY] Enter choice (1 or 2): ");
                            if (console.hasNextLine()) {
                                String choiceLine = console.nextLine().trim();
                                try {
                                    int choice = Integer.parseInt(choiceLine);
                                    if (choice == 1 || choice == 2) {
                                        selectedWorkload = choice;
                                        triggerCompute = true;
                                    } else {
                                        System.out.println("[TELEMETRY] Invalid option. Enter 1 or 2.");
                                    }
                                } catch (NumberFormatException e) {
                                    System.out.println("[TELEMETRY] Invalid number format.");
                                }
                            }
                        }
                        break;
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
                        System.out.println("[TELEMETRY] Unknown command: '" + cmd + "'. Options: START, STATUS, ABORT, EXIT");
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
                    taskStr = agent.currentTask.taskType + "#" + agent.currentTask.sequenceIndex;
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
            System.out.println("║  🛑 EMERGENCY SHUTDOWN: SEVERING AGENT CONNECTIONS         🛑 ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            timeoutScheduler.shutdownNow();
            connectionThreadPool.shutdownNow();

            for (AgentState agent : connectionRegistry.values()) {
                try {
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
}
