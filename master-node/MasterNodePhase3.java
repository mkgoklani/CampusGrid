import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CAMPUS GRID - MASTER NODE CONTROL PLANE - PHASE 3
 * 
 * FAULT TOLERANCE & GLOBAL KILL SWITCH
 * 
 * Extension of Phases 1-2 with enterprise-grade reliability:
 * - Thread-Safe Pending Queue (Phase 3.A): Task dispatch from centralized queue
 * - Fail-Fast Re-Queue (Phase 3.B): Automatic re-assignment on connection loss
 * - Global Kill Switch (Phase 3.C): Poison pill broadcast for instant system abort
 * 
 * Architecture:
 * Phase 1: Multi-threaded socket server (TCP 8080, thread pool, ConcurrentHashMap registry)
 * Phase 2: Scatter-gather synchronization (CountDownLatch, CopyOnWriteArrayList aggregation)
 * Phase 3: Fault tolerance (ConcurrentLinkedQueue, exception re-queuing, poison pill ABORT)
 * 
 * This implementation handles:
 * - Agent node disconnection (gracefully re-queues task)
 * - Network failures (IOException/SocketException recovery)
 * - Manual system abort (ABORT command with poison pill broadcast)
 * - Thread pool emergency shutdown (shutdownNow on ABORT)
 * 
 * @author Campus Grid Engineering Team
 * @version 3.0
 */
public class MasterNodePhase3 {

    // ============================================================================
    // PHASE 1 GLOBALS (Networking Foundation)
    // ============================================================================

    /**
     * Thread-safe registry mapping connected agent IP addresses to their Socket objects.
     */
    private static final ConcurrentHashMap<String, Socket> connectionRegistry = 
        new ConcurrentHashMap<>();

    /**
     * Fixed thread pool executor managing all incoming client connections and distributed tasks.
     */
    private static final ExecutorService connectionThreadPool = 
        Executors.newFixedThreadPool(10);

    /**
     * ServerSocket listening on TCP Port 8080.
     */
    private static ServerSocket masterServerSocket;

    /**
     * Flag to gracefully signal all threads to shut down.
     */
    private static volatile boolean isServerRunning = true;

    // ============================================================================
    // PHASE 2 GLOBALS (Scatter-Gather Coordination)
    // ============================================================================

    /**
     * Thread-safe collection for aggregated results from distributed workers.
     */
    private static final CopyOnWriteArrayList<String> aggregatedResults = 
        new CopyOnWriteArrayList<>();

    /**
     * Synchronization barrier for scatter-gather workload coordination.
     */
    private static CountDownLatch scatterGatherBarrier = new CountDownLatch(5);

    /**
     * Final assembled output after all workers complete.
     */
    private static volatile String finalAssembledOutput = "";

    // ============================================================================
    // PHASE 3 GLOBALS (Fault Tolerance & Kill Switch)
    // ============================================================================

    /**
     * PHASE 3.A: Thread-Safe Pending Queue
     * 
     * This queue acts as the primary waiting room for unsent/un-processed data chunks.
     * Instead of directly assigning tasks to workers, workers actively poll() from this queue
     * to retrieve their next assignment.
     * 
     * ConcurrentLinkedQueue provides:
     * - Lock-free enqueue/dequeue operations
     * - Thread-safe poll() for worker acquisition
     * - Thread-safe add() for re-queuing on failure
     * - No blocking; returns null if queue empty (workers can gracefully handle)
     * 
     * Queue Lifecycle:
     * 1. Initialize with 5 dummy chunks: "Chunk_1", "Chunk_2", etc.
     * 2. Workers poll() to acquire chunks
     * 3. Workers process chunk
     * 4. On success: chunk discarded
     * 5. On failure (connection loss): chunk re-added via queue.add()
     * 6. Another worker eventually polls the re-queued chunk
     * 
     * This ensures NO chunk is lost even if Agent node crashes mid-execution.
     */
    private static final ConcurrentLinkedQueue<String> pendingTaskQueue = 
        new ConcurrentLinkedQueue<>();

    /**
     * Flag indicating whether a system-wide ABORT has been initiated.
     * Set to true when user types "ABORT" command.
     * Prevents new connections from being queued during shutdown.
     */
    private static volatile boolean abortInitiated = false;

    /**
     * Lock object for coordinating abort sequence.
     * Used to ensure abort operations complete atomically.
     */
    private static final Object abortLock = new Object();

    // ============================================================================
    // MAIN ENTRY POINT
    // ============================================================================

    /**
     * Main entry point demonstrating Phases 1-3:
     * 1. Phase 1: Initialize networking (ServerSocket, thread pool, registry)
     * 2. Phase 2: Scatter-gather simulation
     * 3. Phase 3: Fault tolerance & kill switch
     * 
     * @param args Command-line arguments (unused)
     */
    public static void main(String[] args) {
        try {
            // Initialize networking
            masterServerSocket = new ServerSocket(8080);
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  CAMPUS GRID - MASTER NODE (PHASE 1 + 2 + 3)               ║");
            System.out.println("║  Listening on: 0.0.0.0:8080                               ║");
            System.out.println("║  Fault Tolerance: ENABLED (Fail-Fast Re-Queue)            ║");
            System.out.println("║  Kill Switch: ENABLED (Type 'ABORT' for instant shutdown) ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // PHASE 3.A: Initialize pending task queue with 5 dummy chunks
            initializePendingTaskQueue();

            // Start telemetry daemon (enhanced with ABORT command)
            Thread telemetryDaemon = new Thread(MasterNodePhase3::startTelemetryInterface);
            telemetryDaemon.setDaemon(true);
            telemetryDaemon.setName("Telemetry-Daemon");
            telemetryDaemon.start();

            // Start accept loop
            Thread acceptLoopThread = new Thread(MasterNodePhase3::runAcceptLoop);
            acceptLoopThread.setDaemon(false);
            acceptLoopThread.setName("Accept-Loop");
            acceptLoopThread.start();

            Thread.sleep(500);

            // Display initial queue state
            System.out.println("[INIT] Pending Task Queue initialized with " + pendingTaskQueue.size() + " chunks");
            System.out.println("[INIT] Waiting for agent connections...");
            System.out.println("[INIT] Type 'STATUS' to view connected agents");
            System.out.println("[INIT] Type 'ABORT' for emergency system shutdown");
            System.out.println();

            // Keep main thread alive
            acceptLoopThread.join();

        } catch (IOException e) {
            System.err.println("[FATAL] Failed to bind ServerSocket: " + e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            System.err.println("[MAIN] Thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            isServerRunning = false;
            shutdownMasterNode();
        }
    }

    // ============================================================================
    // PHASE 3.A: PENDING TASK QUEUE INITIALIZATION
    // ============================================================================

    /**
     * PHASE 3.A: Initialize the pending task queue with 5 dummy work chunks.
     * 
     * These chunks will be distributed to agent nodes on demand.
     * If an agent crashes, the chunk is re-queued and reassigned.
     */
    private static void initializePendingTaskQueue() {
        pendingTaskQueue.add("Chunk_1_ProcessRequest");
        pendingTaskQueue.add("Chunk_2_ProcessRequest");
        pendingTaskQueue.add("Chunk_3_ProcessRequest");
        pendingTaskQueue.add("Chunk_4_ProcessRequest");
        pendingTaskQueue.add("Chunk_5_ProcessRequest");
    }

    // ============================================================================
    // PHASE 1 CONTINUATION: ACCEPT LOOP
    // ============================================================================

    /**
     * Phase 1 accept loop running on dedicated thread.
     * Continuously accepts incoming TCP connections and submits handlers to thread pool.
     */
    private static void runAcceptLoop() {
        while (isServerRunning && !abortInitiated) {
            try {
                Socket incomingConnection = masterServerSocket.accept();
                String clientIP = incomingConnection.getInetAddress().getHostAddress();
                System.out.println("[ACCEPT] New connection from: " + clientIP);
                connectionRegistry.put(clientIP, incomingConnection);

                // PHASE 3.B: Create handler with reference to pending queue (for re-queuing on failure)
                connectionThreadPool.submit(
                    new AgentConnectionHandler(incomingConnection, clientIP, pendingTaskQueue)
                );

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

    // ============================================================================
    // PHASE 1.D ENHANCEMENT: TELEMETRY INTERFACE WITH ABORT COMMAND
    // ============================================================================

    /**
     * PHASE 1.D Enhanced: Telemetry interface with Phase 3.C ABORT command support.
     * 
     * Supported commands:
     * - STATUS: Display connected agents
     * - ABORT: Execute global kill switch (poison pill)
     * - EXIT: Graceful shutdown
     */
    private static void startTelemetryInterface() {
        try {
            Scanner consoleInput = new Scanner(System.in);
            System.out.println("[TELEMETRY] Diagnostic interface ready. Commands: STATUS, ABORT, EXIT");

            while (isServerRunning && consoleInput.hasNextLine()) {
                String command = consoleInput.nextLine().trim().toUpperCase();

                switch (command) {
                    case "STATUS":
                        displayConnectionStatus();
                        break;

                    case "ABORT":
                        // PHASE 3.C: Execute global kill switch
                        executeGlobalKillSwitch();
                        break;

                    case "EXIT":
                        System.out.println("[TELEMETRY] Graceful shutdown requested.");
                        isServerRunning = false;
                        break;

                    case "":
                        break;

                    default:
                        System.out.println("[TELEMETRY] Unknown command: '" + command + "'");
                        System.out.println("[TELEMETRY] Available: STATUS, ABORT, EXIT");
                }
            }

            consoleInput.close();

        } catch (Exception e) {
            System.err.println("[TELEMETRY] Error: " + e.getMessage());
        }
    }

    /**
     * Display status of connected agents and pending tasks.
     */
    private static void displayConnectionStatus() {
        int totalConnections = connectionRegistry.size();
        int pendingTasks = pendingTaskQueue.size();
        
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  SYSTEM STATUS                                             ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  Connected Agents: " + String.format("%-43s", totalConnections) + "║");
        System.out.println("║  Pending Tasks: " + String.format("%-46s", pendingTasks) + "║");
        System.out.println("║  Thread Pool: 10 (active + available)                      ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");

        if (totalConnections == 0) {
            System.out.println("║  (No agents currently connected)                            ║");
        } else {
            int index = 1;
            for (String agentIP : connectionRegistry.keySet()) {
                System.out.println("║  [" + index + "] " + String.format("%-54s", agentIP) + "║");
                index++;
            }
        }

        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    // ============================================================================
    // PHASE 3.C: GLOBAL KILL SWITCH (POISON PILL)
    // ============================================================================

    /**
     * PHASE 3.C: Execute Global Kill Switch (Poison Pill Broadcast)
     * 
     * This method implements the emergency abort sequence in precise order:
     * 1. Set abort flag to prevent new connections
     * 2. Interrupt all active threads in thread pool (shutdownNow)
     * 3. Iterate through all connected agents and send poison pill (<TERMINATE>)
     * 4. Close all sockets
     * 5. Display abort confirmation
     * 
     * The poison pill mechanism allows agents to detect system abort and shut down gracefully.
     * All operations are coordinated atomically to prevent partial state corruption.
     */
    private static void executeGlobalKillSwitch() {
        synchronized (abortLock) {
            if (abortInitiated) {
                System.out.println("[ABORT] Abort already in progress...");
                return;
            }

            abortInitiated = true;
            isServerRunning = false;

            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  🛑 SYSTEM ABORT INITIATED: POISON PILL BROADCAST 🛑        ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Step 1: Emergency shutdown of thread pool
            System.out.println("[ABORT-KILL-SWITCH] Step 1: Interrupting all active threads...");
            List<Runnable> pendingTasks = connectionThreadPool.shutdownNow();
            System.out.println("[ABORT-KILL-SWITCH] Interrupted " + pendingTasks.size() + " active tasks");

            // Step 2: Broadcast poison pill to all connected agents
            System.out.println("[ABORT-KILL-SWITCH] Step 2: Broadcasting poison pill to agents...");
            int totalAgents = connectionRegistry.size();
            int successfulNotifications = 0;

            for (String agentIP : connectionRegistry.keySet()) {
                try {
                    Socket agentSocket = connectionRegistry.get(agentIP);
                    if (agentSocket != null && !agentSocket.isClosed()) {
                        // Send poison pill message
                        PrintWriter agentOutput = new PrintWriter(
                            agentSocket.getOutputStream(),
                            true
                        );
                        agentOutput.println("<TERMINATE>");
                        agentOutput.flush();
                        
                        System.out.println("[ABORT-KILL-SWITCH] Poison pill sent to: " + agentIP);
                        successfulNotifications++;
                    }
                } catch (IOException e) {
                    System.out.println("[ABORT-KILL-SWITCH] Could not notify " + agentIP + ": " + e.getMessage());
                }
            }

            System.out.println("[ABORT-KILL-SWITCH] Successfully notified " + successfulNotifications + "/" + totalAgents + " agents");

            // Step 3: Force close all sockets
            System.out.println("[ABORT-KILL-SWITCH] Step 3: Force-closing all connections...");
            int closedCount = 0;

            for (String agentIP : connectionRegistry.keySet()) {
                try {
                    Socket agentSocket = connectionRegistry.remove(agentIP);
                    if (agentSocket != null && !agentSocket.isClosed()) {
                        agentSocket.close();
                        closedCount++;
                        System.out.println("[ABORT-KILL-SWITCH] Closed connection: " + agentIP);
                    }
                } catch (IOException e) {
                    System.err.println("[ABORT-KILL-SWITCH] Error closing " + agentIP + ": " + e.getMessage());
                }
            }

            System.out.println("[ABORT-KILL-SWITCH] Force-closed " + closedCount + " connections");

            // Step 4: Display abort confirmation
            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  ✓ SYSTEM ABORT COMPLETED                                  ║");
            System.out.println("║  ✓ ALL CONNECTIONS SEVERED                                 ║");
            System.out.println("║  ✓ THREAD POOL TERMINATED                                  ║");
            System.out.println("║  ✓ POISON PILL BROADCAST COMPLETE                          ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            System.exit(0);
        }
    }

    // ============================================================================
    // PHASE 3.B: AGENT CONNECTION HANDLER (WITH FAULT TOLERANCE)
    // ============================================================================

    /**
     * PHASE 3.B: Agent Connection Handler with Fail-Fast Re-Queue
     * 
     * This handler implements fault tolerance by:
     * 1. Polling the pending task queue to get a work chunk
     * 2. Sending chunk to agent node
     * 3. On success: marking chunk as complete
     * 4. On failure (IOException/SocketException): re-queuing chunk via queue.add()
     * 5. Gracefully terminating and removing from registry
     * 
     * The fail-fast re-queue pattern ensures:
     * - NO chunks are lost if agent crashes mid-execution
     * - Another surviving agent will eventually poll the re-queued chunk
     * - System maintains consistency despite network failures
     */
    private static class AgentConnectionHandler implements Runnable {

        private final Socket clientSocket;
        private final String clientIP;
        private final ConcurrentLinkedQueue<String> taskQueue;
        private String currentAssignment = null;

        /**
         * Constructs handler with task queue reference for re-queuing on failure.
         */
        public AgentConnectionHandler(Socket clientSocket, String clientIP, 
                                     ConcurrentLinkedQueue<String> taskQueue) {
            this.clientSocket = clientSocket;
            this.clientIP = clientIP;
            this.taskQueue = taskQueue;
        }

        /**
         * PHASE 3.B: Main execution with fault tolerance.
         * 
         * Execution Flow:
         * 1. Set up I/O streams
         * 2. Send connection confirmation
         * 3. Poll task queue to get work chunk
         * 4. Try to send chunk to agent
         * 5. Wait for result (simulated with sleep)
         * 6. On exception: catch and re-queue chunk
         * 7. Finally: remove from registry and close socket
         */
        @Override
        public void run() {
            try {
                // Register connection
                System.out.println("[HANDLER] [" + clientIP + "] Connected. Registry size: " + connectionRegistry.size());

                // Set up I/O
                BufferedReader agentInput = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream())
                );
                PrintWriter agentOutput = new PrintWriter(
                    clientSocket.getOutputStream(),
                    true
                );

                // Send confirmation
                agentOutput.println("[MASTER] Welcome to Campus Grid. Requesting task...");

                // PHASE 3.A: Poll pending task queue
                currentAssignment = taskQueue.poll();

                if (currentAssignment == null) {
                    System.out.println("[HANDLER] [" + clientIP + "] No tasks available in queue.");
                    agentOutput.println("[MASTER] No tasks queued. Goodbye.");
                    return;
                }

                System.out.println("[HANDLER] [" + clientIP + "] Polled task from queue: " + currentAssignment);

                // Send task to agent
                agentOutput.println("[MASTER] Your task: " + currentAssignment);
                System.out.println("[HANDLER] [" + clientIP + "] Dispatched: " + currentAssignment);

                // Simulate processing delay (agent executing task)
                Thread.sleep(2000);

                // Wait for agent result
                System.out.println("[HANDLER] [" + clientIP + "] Waiting for agent result...");
                String agentResult = agentInput.readLine();

                if (agentResult == null) {
                    // Client disconnected before sending result
                    throw new SocketException("Agent disconnected without providing result");
                }

                System.out.println("[HANDLER] [" + clientIP + "] Result received: " + agentResult);
                agentOutput.println("[MASTER] Result acknowledged: " + agentResult);

            } catch (SocketException e) {
                // PHASE 3.B: FAIL-FAST RE-QUEUE ON SOCKET EXCEPTION
                System.err.println("[HANDLER] [" + clientIP + "] SocketException: " + e.getMessage());

                if (currentAssignment != null) {
                    System.out.println("[HANDLER] [" + clientIP + "] FAIL-FAST RE-QUEUE: Re-queuing " + currentAssignment);
                    taskQueue.add(currentAssignment);  // Thread-safe re-queue
                    System.out.println("[HANDLER] [" + clientIP + "] Task re-queued. Pending queue size: " + taskQueue.size());
                }

            } catch (IOException e) {
                // PHASE 3.B: FAIL-FAST RE-QUEUE ON IO EXCEPTION
                System.err.println("[HANDLER] [" + clientIP + "] IOException: " + e.getMessage());

                if (currentAssignment != null) {
                    System.out.println("[HANDLER] [" + clientIP + "] FAIL-FAST RE-QUEUE: Re-queuing " + currentAssignment);
                    taskQueue.add(currentAssignment);  // Thread-safe re-queue
                    System.out.println("[HANDLER] [" + clientIP + "] Task re-queued. Pending queue size: " + taskQueue.size());
                }

            } catch (InterruptedException e) {
                System.err.println("[HANDLER] [" + clientIP + "] Thread interrupted: " + e.getMessage());

                if (currentAssignment != null) {
                    System.out.println("[HANDLER] [" + clientIP + "] FAIL-FAST RE-QUEUE: Re-queuing " + currentAssignment);
                    taskQueue.add(currentAssignment);
                }

                Thread.currentThread().interrupt();

            } catch (Exception e) {
                System.err.println("[HANDLER] [" + clientIP + "] Unexpected error: " + e.getMessage());
                e.printStackTrace();

                if (currentAssignment != null) {
                    System.out.println("[HANDLER] [" + clientIP + "] FAIL-FAST RE-QUEUE: Re-queuing " + currentAssignment);
                    taskQueue.add(currentAssignment);
                }

            } finally {
                // GUARANTEED CLEANUP
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                    }
                    connectionRegistry.remove(clientIP);
                    System.out.println("[HANDLER] [" + clientIP + "] Unregistered. Registry size: " + connectionRegistry.size());

                } catch (IOException e) {
                    System.err.println("[HANDLER] [" + clientIP + "] Error during cleanup: " + e.getMessage());
                }
            }
        }
    }

    // ============================================================================
    // GRACEFUL SHUTDOWN
    // ============================================================================

    /**
     * Graceful shutdown sequence for all resources.
     */
    private static void shutdownMasterNode() {
        System.out.println();
        System.out.println("[SHUTDOWN] Initiating shutdown sequence...");

        try {
            if (masterServerSocket != null && !masterServerSocket.isClosed()) {
                masterServerSocket.close();
                System.out.println("[SHUTDOWN] ServerSocket closed.");
            }

            connectionThreadPool.shutdown();
            if (!connectionThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                connectionThreadPool.shutdownNow();
            }
            System.out.println("[SHUTDOWN] Thread pool terminated.");

            for (String agentIP : connectionRegistry.keySet()) {
                Socket clientSocket = connectionRegistry.remove(agentIP);
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            }

            System.out.println("[SHUTDOWN] All connections closed.");
            System.out.println("[SHUTDOWN] Master Node terminated successfully.");

        } catch (IOException e) {
            System.err.println("[SHUTDOWN] IOException: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("[SHUTDOWN] Interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
