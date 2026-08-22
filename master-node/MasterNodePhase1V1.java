import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * CAMPUS GRID - MASTER NODE CONTROL PLANE - PHASE 1 VERSION 1
 * 
 * Principal Java Systems Engineer Implementation
 * 
 * This class represents the central Control Plane of the Campus Grid distributed computing
 * cluster. It operates under a strict Star Topology (Master-Worker model) where this node
 * dictates all network interactions and maintains the authoritative connection registry.
 * 
 * The MasterNodePhase1V1 handles:
 * - Simultaneous TCP connections on port 8080 via ServerSocket
 * - Thread-safe connection state management using ConcurrentHashMap
 * - Asynchronous request handling via ExecutorService thread pool
 * - Real-time diagnostic telemetry through an interactive CLI interface
 * 
 * PHASE 1 ARCHITECTURE:
 * - Phase 1.A: Single-threaded foundation with graceful exception handling
 * - Phase 1.B: Concurrency engine utilizing ExecutorService for multi-threaded processing
 * - Phase 1.C: State manager with ConcurrentHashMap for thread-safe client registry
 * - Phase 1.D: Telemetry interface for real-time diagnostic monitoring
 * 
 * @author Campus Grid Engineering Team
 * @version 1.0
 */
public class MasterNodePhase1V1 {

    // ============================================================================
    // GLOBAL STATE & CONCURRENCY PRIMITIVES
    // ============================================================================

    /** 
     * Thread-safe registry mapping connected agent IP addresses to their Socket objects.
     * This ConcurrentHashMap guarantees that multiple threads can safely read/write
     * without explicit synchronization, preventing race conditions and deadlocks.
     * 
     * Key: Client IP Address (String)
     * Value: Socket object representing the active connection
     */
    private static final ConcurrentHashMap<String, Socket> connectionRegistry = 
        new ConcurrentHashMap<>();

    /**
     * Fixed thread pool executor managing all incoming client connections.
     * Configured with a pool size of 10 to strictly prevent thread exhaustion
     * and ensure predictable resource consumption on the Master Node.
     * 
     * Each thread in the pool is assigned one AgentConnectionHandler task,
     * allowing up to 10 concurrent agent connections to be processed in parallel.
     */
    private static final ExecutorService connectionThreadPool = 
        Executors.newFixedThreadPool(10);

    /**
     * ServerSocket listening on the designated Master Node port (TCP 8080).
     * This socket remains in blocking accept() state until a new connection arrives.
     * Upon connection, a Socket is returned and immediately passed to the thread pool.
     */
    private static ServerSocket masterServerSocket;

    /**
     * Flag to gracefully signal all threads to shut down.
     * When set to false, the main accept loop and telemetry daemon will exit.
     */
    private static volatile boolean isServerRunning = true;

    /**
     * Synchronization point for the telemetry daemon thread.
     * Used to ensure the daemon thread is fully initialized before the main thread
     * releases all resources.
     */
    private static final Object telemetryLock = new Object();

    // ============================================================================
    // MAIN ENTRY POINT
    // ============================================================================

    /**
     * Main entry point for the Campus Grid Master Node.
     * 
     * Initializes the ServerSocket on port 8080 and starts two independent execution paths:
     * 1. Main thread: Accepts incoming TCP connections in a blocking loop
     * 2. Daemon thread: Monitors System.in for diagnostic commands (e.g., STATUS)
     * 
     * The architecture ensures that incoming connections are immediately offloaded to
     * the thread pool, preventing the accept loop from blocking on I/O operations.
     * 
     * @param args Command-line arguments (currently unused)
     */
    public static void main(String[] args) {
        try {
            // Initialize the ServerSocket on port 8080
            masterServerSocket = new ServerSocket(8080);
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  CAMPUS GRID - MASTER NODE CONTROL PLANE - PHASE 1 V1      ║");
            System.out.println("║  Listening on: 0.0.0.0:8080                               ║");
            System.out.println("║  Thread Pool Size: 10                                      ║");
            System.out.println("║  Type 'STATUS' to view connected agents                   ║");
            System.out.println("║  Type 'EXIT' to shutdown the Master Node                  ║");
            System.out.println("╚════════════════════════════════════════════════════════════╝");
            System.out.println();

            // PHASE 1.D: Start the telemetry daemon thread for diagnostic monitoring
            Thread telemetryDaemon = new Thread(MasterNodePhase1V1::startTelemetryInterface);
            telemetryDaemon.setDaemon(true);
            telemetryDaemon.setName("Telemetry-Daemon");
            telemetryDaemon.start();

            // PHASE 1.A & 1.B: Main accept loop - continuously accept incoming connections
            // Each connection is immediately wrapped in an AgentConnectionHandler and
            // submitted to the thread pool, ensuring non-blocking client handling.
            while (isServerRunning) {
                try {
                    Socket incomingConnection = masterServerSocket.accept();
                    String clientIP = incomingConnection.getInetAddress().getHostAddress();
                    System.out.println("[ACCEPT] New connection from: " + clientIP);

                    // PHASE 1.B: Submit the connection handler to the thread pool
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

        } catch (IOException e) {
            System.err.println("[FATAL] Failed to bind ServerSocket on port 8080: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Graceful shutdown sequence
            isServerRunning = false;
            shutdownMasterNode();
        }
    }

    // ============================================================================
    // PHASE 1.D: TELEMETRY INTERFACE
    // ============================================================================

    /**
     * PHASE 1.D: Telemetry Interface
     * 
     * This method runs on a dedicated daemon thread and continuously monitors
     * System.in for diagnostic commands from the Master Node operator.
     * 
     * Supported Commands:
     * - STATUS: Display all currently connected agents with their IP addresses and status
     * - EXIT: Gracefully shutdown the entire Master Node and all connection handlers
     * 
     * The telemetry interface ensures that operator commands do not block the
     * main accept loop, enabling real-time monitoring without degrading throughput.
     */
    private static void startTelemetryInterface() {
        try {
            Scanner consoleInput = new Scanner(System.in);
            System.out.println("[TELEMETRY] Diagnostic interface initialized. Ready for commands.");

            while (isServerRunning && consoleInput.hasNextLine()) {
                String command = consoleInput.nextLine().trim().toUpperCase();

                switch (command) {
                    case "STATUS":
                        displayConnectionStatus();
                        break;

                    case "EXIT":
                        System.out.println("[TELEMETRY] Shutdown command received. Initiating graceful termination...");
                        isServerRunning = false;
                        break;

                    case "":
                        // Ignore empty lines
                        break;

                    default:
                        System.out.println("[TELEMETRY] Unknown command: '" + command + "'");
                        System.out.println("[TELEMETRY] Available commands: STATUS, EXIT");
                }
            }

            consoleInput.close();

        } catch (Exception e) {
            System.err.println("[TELEMETRY] Error in diagnostic interface: " + e.getMessage());
        }
    }

    /**
     * Displays the current status of all connected agents.
     * 
     * This method safely evaluates the ConcurrentHashMap without acquiring
     * any explicit locks (the ConcurrentHashMap guarantees atomic visibility).
     * The iteration is snapshot-consistent—it reflects the state at the moment
     * the iterator was created.
     * 
     * Output includes:
     * - Total number of connected agents
     * - Detailed list of each agent's IP address
     * - Thread pool statistics
     */
    private static void displayConnectionStatus() {
        synchronized (connectionRegistry) {
            int totalConnections = connectionRegistry.size();
            System.out.println();
            System.out.println("╔════════════════════════════════════════════════════════════╗");
            System.out.println("║  ACTIVE AGENT CONNECTIONS                                  ║");
            System.out.println("╠════════════════════════════════════════════════════════════╣");
            System.out.println("║  Total Connected Agents: " + String.format("%-38s", totalConnections) + "║");
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
    }

    // ============================================================================
    // GRACEFUL SHUTDOWN HANDLER
    // ============================================================================

    /**
     * Gracefully shuts down the Master Node, ensuring all resources are properly
     * released and all active connections are cleanly terminated.
     * 
     * Shutdown Sequence:
     * 1. Reject new incoming connections
     * 2. Interrupt all remaining thread pool tasks
     * 3. Close all active agent connections
     * 4. Close the ServerSocket
     * 
     * This fail-fast approach ensures that even if some connections are hung,
     * the entire system will terminate cleanly within a bounded time window.
     */
    private static void shutdownMasterNode() {
        System.out.println();
        System.out.println("[SHUTDOWN] Initiating graceful termination sequence...");

        try {
            // Step 1: Close the ServerSocket to stop accepting new connections
            if (masterServerSocket != null && !masterServerSocket.isClosed()) {
                masterServerSocket.close();
                System.out.println("[SHUTDOWN] ServerSocket closed successfully.");
            }

            // Step 2: Shut down the thread pool
            connectionThreadPool.shutdown();
            if (!connectionThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.out.println("[SHUTDOWN] Thread pool termination timeout. Forcing immediate shutdown...");
                connectionThreadPool.shutdownNow();
            }
            System.out.println("[SHUTDOWN] Thread pool shut down successfully.");

            // Step 3: Close all remaining client connections
            for (String agentIP : connectionRegistry.keySet()) {
                Socket clientSocket = connectionRegistry.remove(agentIP);
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                    System.out.println("[SHUTDOWN] Closed connection from: " + agentIP);
                }
            }

            System.out.println("[SHUTDOWN] Master Node terminated successfully.");

        } catch (IOException e) {
            System.err.println("[SHUTDOWN] IOException during shutdown: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("[SHUTDOWN] Thread interrupted during shutdown: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================================
    // PHASE 1.B & 1.C: AGENT CONNECTION HANDLER
    // ============================================================================

    /**
     * PHASE 1.B & 1.C: Agent Connection Handler
     * 
     * This inner class implements Runnable and represents a single connection handler
     * thread for an incoming agent connection. Each handler is responsible for:
     * 
     * 1. Registering the connection in the ConcurrentHashMap (PHASE 1.C)
     * 2. Reading incoming data from the agent
     * 3. Processing and echoing confirmations
     * 4. Safely removing the connection from the registry upon disconnection
     * 
     * Thread Safety:
     * - Uses ConcurrentHashMap for lockless thread-safe access
     * - Guarantees connection state is visible to all threads without explicit locks
     * - Prevents race conditions through atomic put() and remove() operations
     * 
     * @author Campus Grid Engineering Team
     */
    private static class AgentConnectionHandler implements Runnable {

        private final Socket clientSocket;
        private final String clientIP;

        /**
         * Constructs a new AgentConnectionHandler for a specific client connection.
         * 
         * @param clientSocket The Socket object representing the agent connection
         * @param clientIP The IP address of the connected agent
         */
        public AgentConnectionHandler(Socket clientSocket, String clientIP) {
            this.clientSocket = clientSocket;
            this.clientIP = clientIP;
        }

        /**
         * Main execution method for this connection handler thread.
         * 
         * This method implements the complete lifecycle of a client connection:
         * 1. Register the connection in the ConcurrentHashMap
         * 2. Set up input/output streams
         * 3. Enter a read loop to continuously process incoming data
         * 4. Send confirmations back to the client
         * 5. Handle exceptions gracefully and unregister upon disconnect
         * 
         * The method uses a finally block to GUARANTEE that the connection is
         * removed from the registry, even if an unexpected exception occurs.
         * This is critical to prevent resource leaks and stale connection entries.
         */
        @Override
        public void run() {
            try {
                // PHASE 1.C: Register this connection in the ConcurrentHashMap
                connectionRegistry.put(clientIP, clientSocket);
                System.out.println("[HANDLER] [" + clientIP + "] Registered in connection registry.");
                System.out.println("[HANDLER] [" + clientIP + "] Current active connections: " + connectionRegistry.size());

                // Set up input stream to read from the agent
                BufferedReader agentInput = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream())
                );

                // Set up output stream to send to the agent
                PrintWriter agentOutput = new PrintWriter(
                    clientSocket.getOutputStream(),
                    true  // autoFlush enabled for immediate transmission
                );

                // Send initial connection confirmation
                agentOutput.println("[MASTER] Welcome to Campus Grid. You are connected to the Master Node.");

                String incomingData;
                int messageCount = 0;

                // PHASE 1.A: Blocking read loop - continuously process agent messages
                while ((incomingData = agentInput.readLine()) != null) {
                    messageCount++;
                    System.out.println("[HANDLER] [" + clientIP + "] Message #" + messageCount + ": " + incomingData);

                    // Echo confirmation back to the agent
                    agentOutput.println("[MASTER] Received and processed: " + incomingData);
                }

                System.out.println("[HANDLER] [" + clientIP + "] Connection closed by agent after " + messageCount + " messages.");

            } catch (SocketException e) {
                // SocketException typically indicates the client disconnected abruptly
                System.out.println("[HANDLER] [" + clientIP + "] SocketException (client disconnect): " + e.getMessage());

            } catch (IOException e) {
                // IOException covers other I/O failures (stream closure, read errors, etc.)
                System.out.println("[HANDLER] [" + clientIP + "] IOException: " + e.getMessage());

            } catch (Exception e) {
                // Catch-all for unexpected exceptions
                System.err.println("[HANDLER] [" + clientIP + "] Unexpected exception: " + e.getMessage());
                e.printStackTrace();

            } finally {
                // PHASE 1.C: GUARANTEED removal from registry - prevents resource leaks
                // This block executes even if exceptions occur above.
                try {
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                    }
                    connectionRegistry.remove(clientIP);
                    System.out.println("[HANDLER] [" + clientIP + "] Unregistered from connection registry.");
                    System.out.println("[HANDLER] [" + clientIP + "] Remaining active connections: " + connectionRegistry.size());

                } catch (IOException e) {
                    System.err.println("[HANDLER] [" + clientIP + "] Error closing socket: " + e.getMessage());
                }
            }
        }
    }
}
