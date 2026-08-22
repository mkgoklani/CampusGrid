import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CAMPUS GRID - MASTER NODE CONTROL PLANE - PHASE 1 VERSION 2
 * 
 * SYNCHRONIZATION BARRIER & SCATTER-GATHER ROUTING
 * 
 * Extension of Phase 1 with advanced data coordination:
 * - Scatter: Split conceptual workload into 5 discrete chunks
 * - Dispatch: Delegate chunks to worker threads via ExecutorService
 * - Gather: Aggregate results from unpredictable, overlapping worker threads
 * - Stitch: Wait for all 5 workers via CountDownLatch, reassemble output
 * 
 * This implementation demonstrates:
 * - Thread-safe result aggregation (CopyOnWriteArrayList)
 * - Synchronization barrier pattern (CountDownLatch)
 * - Worker thread callbacks with state coordination
 * - Race condition prevention during concurrent writes
 * 
 * @author Campus Grid Engineering Team
 * @version 2.0
 */
public class MasterNodePhase1V2 {

    // ============================================================================
    // PHASE 1 GLOBALS (Inherited from Phase 1)
    // ============================================================================

    /**
     * Thread-safe registry mapping connected agent IP addresses to their Socket objects.
     */
    private static final ConcurrentHashMap<String, Socket> connectionRegistry = 
        new ConcurrentHashMap<>();

    /**
     * Fixed thread pool executor managing all incoming client connections and scatter-gather tasks.
     * Configured with pool size of 10 to handle both agent connections and worker tasks.
     */
    private static final ExecutorService connectionThreadPool = 
        Executors.newFixedThreadPool(10);

    /**
     * ServerSocket listening on the designated Master Node port (TCP 8080).
     */
    private static ServerSocket masterServerSocket;

    /**
     * Flag to gracefully signal all threads to shut down.
     */
    private static volatile boolean isServerRunning = true;

    // ============================================================================
    // PHASE 2 GLOBALS (New for Scatter-Gather)
    // ============================================================================

    /**
     * PHASE 2.A: Thread-Safe Data Aggregator
     * 
     * CopyOnWriteArrayList is specifically chosen for thread-safe collection of returned
     * data chunks from multiple worker threads. Unlike standard ArrayList, this collection
     * prevents data corruption through copy-on-write semantics:
     * 
     * When a write (add) occurs, the underlying array is copied and updated in a new reference,
     * ensuring that ongoing iterations and reads see a consistent snapshot without blocking.
     * This eliminates race conditions where parallel thread writes would corrupt memory.
     * 
     * Performance Trade-off: Writes are slower (due to array copy) but reads and iteration
     * are fast and never block. This is optimal for our scatter-gather pattern where reads
     * (iteration) outnumber writes (5 workers adding once each).
     */
    private static final CopyOnWriteArrayList<String> aggregatedResults = 
        new CopyOnWriteArrayList<>();

    /**
     * PHASE 2.B: Synchronization Barrier
     * 
     * CountDownLatch initialized to 5, representing the 5 required data chunks.
     * The main thread will call await() and block until exactly 5 workers have called
     * countDown(), ensuring all scatter-gather tasks complete before reassembly.
     * 
     * CountDownLatch is reusable per scatter-gather cycle (create new instance for each cycle).
     */
    private static CountDownLatch scatterGatherBarrier = new CountDownLatch(5);

    /**
     * PHASE 2.D: Stitch & Print Results
     * 
     * This string will hold the final assembled output after all workers complete
     * and the CountDownLatch reaches zero.
     */
    private static volatile String finalAssembledOutput = "";

    // ============================================================================
    // MAIN ENTRY POINT
    // ============================================================================

    /**
     * Main entry point demonstrating Phase 1 (networking) and Phase 2 (scatter-gather).
     * 
     * For Phase 2 testing purposes, this implementation includes:
     * 1. Phase 1: Network initialization (ServerSocket, thread pool)
     * 2. Phase 2: Scatter-gather simulation with 5 dummy worker tasks
     * 3. Verification: Output final assembled result
     * 
     * @param args Command-line arguments (currently unused)
     */
    public static void main(String[] args) {
        try {
            // PHASE 1: Initialize networking components
            masterServerSocket = new ServerSocket(8080);
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  CAMPUS GRID - MASTER NODE (PHASE 1 + 2) - PHASE 1 V2      ║");
            System.out.println("║  Listening on: 0.0.0.0:8080                               ║");
            System.out.println("║  Scatter-Gather Simulation: 5 Worker Tasks                ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Start Phase 1 telemetry daemon
            Thread telemetryDaemon = new Thread(MasterNodePhase1V2::startTelemetryInterface);
            telemetryDaemon.setDaemon(true);
            telemetryDaemon.setName("Telemetry-Daemon");
            telemetryDaemon.start();

            // Start Phase 1 accept loop in background
            Thread acceptLoopThread = new Thread(MasterNodePhase1V2::runAcceptLoop);
            acceptLoopThread.setDaemon(false);
            acceptLoopThread.setName("Accept-Loop");
            acceptLoopThread.start();

            // Give Phase 1 time to initialize
            Thread.sleep(500);

            // ====================================================================
            // PHASE 2: SCATTER-GATHER SIMULATION
            // ====================================================================

            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  PHASE 2: SCATTER-GATHER BARRIER TEST                      ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            executeScatterGatherSimulation();

            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  PHASE 2 SIMULATION COMPLETE                               ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Keep server running for optional manual testing
            System.out.println("[MAIN] Server running. Type 'EXIT' to shutdown.");
            acceptLoopThread.join();

        } catch (IOException e) {
            System.err.println("[FATAL] Failed to bind ServerSocket on port 8080: " + e.getMessage());
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
    // PHASE 1 CONTINUATION (Accept Loop)
    // ============================================================================

    /**
     * Phase 1 accept loop running in background thread.
     * Allows for simultaneous network connections while Phase 2 scatter-gather executes.
     */
    private static void runAcceptLoop() {
        while (isServerRunning) {
            try {
                Socket incomingConnection = masterServerSocket.accept();
                String clientIP = incomingConnection.getInetAddress().getHostAddress();
                System.out.println("[ACCEPT] New connection from: " + clientIP);
                connectionThreadPool.submit(new AgentConnectionHandler(incomingConnection, clientIP));

            } catch (SocketException e) {
                if (isServerRunning) {
                    System.err.println("[ERROR] SocketException in accept loop: " + e.getMessage());
                }
            } catch (IOException e) {
                if (isServerRunning) {
                    System.err.println("[ERROR] IOException in accept loop: " + e.getMessage());
                }
            }
        }
    }

    // ============================================================================
    // PHASE 2.A & 2.B & 2.C: SCATTER-GATHER SIMULATION
    // ============================================================================

    /**
     * PHASE 2: Execute Scatter-Gather Simulation
     * 
     * This method demonstrates the complete scatter-gather-stitch lifecycle:
     * 
     * 1. SCATTER: Conceptually split a large workload into 5 chunks
     * 2. DISPATCH: Submit 5 DummyTaskWorker tasks to the ExecutorService
     * 3. GATHER: Main thread waits on CountDownLatch until all 5 complete
     * 4. STITCH: Reassemble the 5 data chunks into final output
     * 
     * This simulates a real-world scenario where the Master Node needs to:
     * - Delegate work to distributed workers
     * - Handle unpredictable completion times
     * - Wait for all results before proceeding
     * - Prevent race conditions during aggregation
     */
    private static void executeScatterGatherSimulation() {
        // Reset for this cycle
        aggregatedResults.clear();
        scatterGatherBarrier = new CountDownLatch(5);

        System.out.println("[SCATTER-GATHER] Initiating workload scatter...");
        System.out.println("[SCATTER-GATHER] Creating 5 simulated work chunks:");
        System.out.println();

        // PHASE 2.A & 2.C: SCATTER & DISPATCH
        // Create 5 independent dummy worker tasks and submit to thread pool
        for (int chunkIndex = 1; chunkIndex <= 5; chunkIndex++) {
            String chunkDescription = "Chunk_" + chunkIndex + "_Task";
            
            System.out.println("  [" + chunkIndex + "] Dispatching: " + chunkDescription);

            // PHASE 2.C: Create worker thread with barrier and aggregator references
            DummyTaskWorker worker = new DummyTaskWorker(
                chunkIndex,
                chunkDescription,
                scatterGatherBarrier,
                aggregatedResults
            );

            // Submit to thread pool for async execution
            connectionThreadPool.submit(worker);
        }

        System.out.println();
        System.out.println("[SCATTER-GATHER] All 5 tasks dispatched. Main thread entering barrier...");
        System.out.println("[SCATTER-GATHER] Main thread: BLOCKING on CountDownLatch.await()");
        System.out.println();

        // PHASE 2.B: SYNCHRONIZATION BARRIER
        // Main thread blocks here until all 5 workers call countDown()
        try {
            long startTime = System.currentTimeMillis();
            
            // This await() will block until the latch count reaches 0
            scatterGatherBarrier.await();
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            
            System.out.println();
            System.out.println("[SCATTER-GATHER] ✓ CountDownLatch reached ZERO!");
            System.out.println("[SCATTER-GATHER] Main thread resumed after " + elapsedTime + "ms");
            System.out.println("[SCATTER-GATHER] All 5 worker tasks completed.");
            System.out.println();

        } catch (InterruptedException e) {
            System.err.println("[SCATTER-GATHER] FATAL: Main thread interrupted during await()!");
            System.err.println("[SCATTER-GATHER] Error: " + e.getMessage());
            Thread.currentThread().interrupt();
            return;
        }

        // PHASE 2.D: STITCH & PRINT
        // Reassemble the 5 data chunks into final output
        System.out.println("[SCATTER-GATHER] Stitching results together...");
        System.out.println("[SCATTER-GATHER] Aggregated Results Container Size: " + aggregatedResults.size());
        System.out.println();

        // Verify we have exactly 5 results
        if (aggregatedResults.size() != 5) {
            System.err.println("[SCATTER-GATHER] WARNING: Expected 5 results, got " + aggregatedResults.size());
        }

        // Combine all results into final output
        StringBuilder finalOutput = new StringBuilder();
        finalOutput.append("FINAL_ASSEMBLED_OUTPUT: [");

        for (int i = 0; i < aggregatedResults.size(); i++) {
            finalOutput.append(aggregatedResults.get(i));
            if (i < aggregatedResults.size() - 1) {
                finalOutput.append(" | ");
            }
        }

        finalOutput.append("]");
        finalAssembledOutput = finalOutput.toString();

        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  SCATTER-GATHER ASSEMBLY COMPLETE                          ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║  " + finalAssembledOutput);
        System.out.println("╚════════════════════════════════════════════════════════════╝");
    }

    // ============================================================================
    // PHASE 2.C: DUMMY TASK WORKER (Simulated Workload)
    // ============================================================================

    /**
     * PHASE 2.C: Dummy Task Worker
     * 
     * This class represents a simulated distributed worker task that:
     * 1. Sleeps for a randomized duration (1000ms - 5000ms) to simulate work
     * 2. Appends its result to the thread-safe CopyOnWriteArrayList
     * 3. Signals completion by calling CountDownLatch.countDown()
     * 
     * Multiple instances run concurrently in the ExecutorService thread pool,
     * arriving at unpredictable, overlapping times. The CopyOnWriteArrayList
     * and CountDownLatch coordinate the synchronization without deadlock or corruption.
     */
    private static class DummyTaskWorker implements Runnable {

        private final int taskId;
        private final String chunkData;
        private final CountDownLatch completionBarrier;
        private final CopyOnWriteArrayList<String> resultContainer;

        /**
         * Constructs a new DummyTaskWorker.
         * 
         * PHASE 2.C: Worker Thread Constructor
         * The worker is initialized with references to the shared coordination mechanisms:
         * - CountDownLatch: To signal completion
         * - CopyOnWriteArrayList: To safely append results
         * 
         * @param taskId Unique identifier for this task (1-5)
         * @param chunkData Simulated data payload for this chunk
         * @param completionBarrier CountDownLatch to signal task completion
         * @param resultContainer Thread-safe list for result aggregation
         */
        public DummyTaskWorker(int taskId, String chunkData, CountDownLatch completionBarrier,
                              CopyOnWriteArrayList<String> resultContainer) {
            this.taskId = taskId;
            this.chunkData = chunkData;
            this.completionBarrier = completionBarrier;
            this.resultContainer = resultContainer;
        }

        /**
         * PHASE 2.C: Worker Thread Execution
         * 
         * Execution Lifecycle:
         * 1. Log task start
         * 2. Simulate processing delay (random 1-5 seconds)
         * 3. Append result to CopyOnWriteArrayList (thread-safe)
         * 4. Call CountDownLatch.countDown() to signal completion
         * 5. Main thread's await() resumes when latch reaches 0
         * 
         * Thread Safety Notes:
         * - add() to CopyOnWriteArrayList is atomic; no external sync needed
         * - countDown() is atomic; no external sync needed
         * - No race conditions even with 5 threads racing to complete
         */
        @Override
        public void run() {
            try {
                // Calculate random processing time (1-5 seconds)
                long processingTimeMs = 1000 + new Random().nextInt(4000);
                
                System.out.println("[WORKER-" + taskId + "] Starting task. Processing time: " + processingTimeMs + "ms");
                System.out.println("[WORKER-" + taskId + "] Task data: " + chunkData);

                // PHASE 2.C: Simulate processing with Thread.sleep()
                Thread.sleep(processingTimeMs);

                System.out.println("[WORKER-" + taskId + "] ✓ Processing complete! Appending result...");

                // PHASE 2.C: Append result to thread-safe aggregator
                // CopyOnWriteArrayList.add() is atomic; prevents data corruption
                String completionMessage = chunkData + "_Completed_" + processingTimeMs + "ms";
                resultContainer.add(completionMessage);

                System.out.println("[WORKER-" + taskId + "] ✓ Result appended to aggregator.");
                System.out.println("[WORKER-" + taskId + "] Calling CountDownLatch.countDown()...");

                // PHASE 2.B & 2.C: Signal to main thread that this worker is done
                // When the 5th worker calls countDown(), the latch reaches 0 and main thread resumes
                completionBarrier.countDown();

                long remainingCount = completionBarrier.getCount();
                System.out.println("[WORKER-" + taskId + "] Latch count after countDown: " + remainingCount);
                System.out.println("[WORKER-" + taskId + "] COMPLETE");
                System.out.println();

            } catch (InterruptedException e) {
                System.err.println("[WORKER-" + taskId + "] INTERRUPTED: " + e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[WORKER-" + taskId + "] ERROR: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ============================================================================
    // PHASE 1.D: TELEMETRY INTERFACE (Inherited)
    // ============================================================================

    /**
     * Phase 1.D: Telemetry interface for diagnostics.
     */
    private static void startTelemetryInterface() {
        try {
            Scanner consoleInput = new Scanner(System.in);

            while (isServerRunning && consoleInput.hasNextLine()) {
                String command = consoleInput.nextLine().trim().toUpperCase();

                switch (command) {
                    case "STATUS":
                        displayConnectionStatus();
                        break;

                    case "EXIT":
                        System.out.println("[TELEMETRY] Shutdown command received.");
                        isServerRunning = false;
                        break;

                    case "":
                        break;

                    default:
                        System.out.println("[TELEMETRY] Unknown command: '" + command + "'");
                }
            }

            consoleInput.close();

        } catch (Exception e) {
            System.err.println("[TELEMETRY] Error: " + e.getMessage());
        }
    }

    /**
     * Display connection status.
     */
    private static void displayConnectionStatus() {
        int totalConnections = connectionRegistry.size();
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║  ACTIVE AGENT CONNECTIONS: " + totalConnections);
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        for (String agentIP : connectionRegistry.keySet()) {
            System.out.println("  - " + agentIP);
        }
        System.out.println();
    }

    // ============================================================================
    // PHASE 1: AGENT CONNECTION HANDLER (Inherited)
    // ============================================================================

    /**
     * Phase 1.B & 1.C: Agent Connection Handler from Phase 1.
     */
    private static class AgentConnectionHandler implements Runnable {

        private final Socket clientSocket;
        private final String clientIP;

        public AgentConnectionHandler(Socket clientSocket, String clientIP) {
            this.clientSocket = clientSocket;
            this.clientIP = clientIP;
        }

        @Override
        public void run() {
            try {
                connectionRegistry.put(clientIP, clientSocket);
                System.out.println("[HANDLER] [" + clientIP + "] Connected. Total: " + connectionRegistry.size());

                BufferedReader agentInput = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream())
                );

                PrintWriter agentOutput = new PrintWriter(
                    clientSocket.getOutputStream(),
                    true
                );

                agentOutput.println("[MASTER] Welcome to Campus Grid.");

                String incomingData;
                while ((incomingData = agentInput.readLine()) != null) {
                    System.out.println("[HANDLER] [" + clientIP + "] " + incomingData);
                    agentOutput.println("[MASTER] Received: " + incomingData);
                }

            } catch (SocketException e) {
                System.out.println("[HANDLER] [" + clientIP + "] Disconnected.");

            } catch (IOException e) {
                System.out.println("[HANDLER] [" + clientIP + "] Error: " + e.getMessage());

            } finally {
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                    }
                    connectionRegistry.remove(clientIP);

                } catch (IOException e) {
                    System.err.println("[HANDLER] [" + clientIP + "] Close error: " + e.getMessage());
                }
            }
        }
    }

    // ============================================================================
    // GRACEFUL SHUTDOWN (Phase 1)
    // ============================================================================

    /**
     * Graceful shutdown sequence.
     */
    private static void shutdownMasterNode() {
        System.out.println("[SHUTDOWN] Initiating shutdown sequence...");

        try {
            if (masterServerSocket != null && !masterServerSocket.isClosed()) {
                masterServerSocket.close();
            }

            connectionThreadPool.shutdown();
            if (!connectionThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                connectionThreadPool.shutdownNow();
            }

            for (String agentIP : connectionRegistry.keySet()) {
                Socket clientSocket = connectionRegistry.remove(agentIP);
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            }

            System.out.println("[SHUTDOWN] Master Node terminated.");

        } catch (IOException e) {
            System.err.println("[SHUTDOWN] IOException: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("[SHUTDOWN] Interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
