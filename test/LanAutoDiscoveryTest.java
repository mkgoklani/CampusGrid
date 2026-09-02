import java.io.*;
import java.net.*;
import com.campusgrid.agent.network.MasterFinder;
import com.campusgrid.core.*;

/**
 * End-to-end Integration Test for Zero-Configuration Local LAN Auto-Discovery:
 * 1. Master Node UDP Discovery Beacon (MasterDiscoveryBeacon)
 * 2. Agent Node UDP Discovery Probe (MasterFinder)
 * 3. Immediate sub-millisecond response & auto-resolution of LAN IP / TCP port
 * 4. Automatic TCP connection establishment to discovered Master
 */
public class LanAutoDiscoveryTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING LAN AUTO-DISCOVERY INTEGRATION TEST ===");

        int agentTcpPort = 8089;
        int dashboardHttpPort = 8083;
        int dashboardWsPort = 8084;
        int discoveryPort = 8899; // Dedicated test UDP discovery port

        // 1. Initialize Master Node components and custom-port discovery beacon
        WorkerRegistry workerRegistry = new WorkerRegistry();
        JobManager jobManager = new JobManager();
        BenchmarkManager benchmarkManager = new BenchmarkManager(workerRegistry);
        AgentVersionManager versionManager = new AgentVersionManager();
        MasterDiscoveryBeacon beacon = new MasterDiscoveryBeacon(discoveryPort, agentTcpPort, dashboardHttpPort, "CampusGrid-Test-Master");

        // Start Master TCP Socket
        ServerSocket masterServerSocket = new ServerSocket(agentTcpPort);
        System.out.println("[TEST] Master TCP Server listening on port: " + agentTcpPort);

        beacon.start();
        System.out.println("[TEST] MasterDiscoveryBeacon started on UDP port: " + discoveryPort);

        try {
            // 2. Agent Auto-Discovery with zero manual IP configuration
            System.out.println("\n--- Step 1: Agent Probing LAN for Master Node ---");
            long startTime = System.currentTimeMillis();
            MasterFinder.DiscoveredMaster discovered = MasterFinder.discoverMaster(discoveryPort, 3000);
            long elapsed = System.currentTimeMillis() - startTime;

            System.out.printf("[TEST] Discovery completed in %d ms\n", elapsed);
            assert discovered != null : "MasterFinder failed to discover Master Node on LAN";

            System.out.println("[TEST] Discovered Master Name : " + discovered.getName());
            System.out.println("[TEST] Discovered Master IP   : " + discovered.getIpAddress());
            System.out.println("[TEST] Discovered TCP Port    : " + discovered.getTcpPort());
            System.out.println("[TEST] Discovered HTTP Port   : " + discovered.getHttpPort());

            assert "CampusGrid-Test-Master".equals(discovered.getName()) : "Incorrect Master name";
            assert discovered.getTcpPort() == agentTcpPort : "Discovered TCP port does not match (" + discovered.getTcpPort() + " vs " + agentTcpPort + ")";
            assert discovered.getHttpPort() == dashboardHttpPort : "Discovered HTTP port does not match";
            assert discovered.getIpAddress() != null && !discovered.getIpAddress().isEmpty() : "Discovered IP is null/empty";

            System.out.println("[TEST] Step 1 PASSED: Master Node successfully auto-discovered over UDP!");

            // 3. Connect to Auto-Discovered Master
            System.out.println("\n--- Step 2: Establishing TCP Connection to Discovered Master ---");
            Socket agentSocket = new Socket(discovered.getIpAddress(), discovered.getTcpPort());
            Socket serverAcceptedSocket = masterServerSocket.accept();

            ObjectOutputStream agentOut = new ObjectOutputStream(agentSocket.getOutputStream());
            agentOut.flush();
            ObjectOutputStream serverOut = new ObjectOutputStream(serverAcceptedSocket.getOutputStream());
            serverOut.flush();

            ObjectInputStream agentIn = new ObjectInputStream(agentSocket.getInputStream());
            ObjectInputStream serverIn = new ObjectInputStream(serverAcceptedSocket.getInputStream());

            // Send Heartbeat over discovered connection
            agentOut.writeObject(new GridMessage(MessageType.HEARTBEAT, "DISCOVERED_AGENT_01",
                new HeartbeatPayload(48, 25.5, WorkerStatus.IDLE, 5.0, "macOS", "Apple M1", "ARM64", "M1 GPU", "METAL", true, true, "1.0.2", 102)
            ));
            agentOut.flush();

            Object received = serverIn.readObject();
            assert received instanceof GridMessage : "Server did not receive GridMessage";
            GridMessage gm = (GridMessage) received;
            assert gm.getType() == MessageType.HEARTBEAT : "Expected HEARTBEAT message";
            System.out.println("[TEST] Master received heartbeat from auto-discovered agent: " + gm);

            agentSocket.close();
            serverAcceptedSocket.close();

            System.out.println("[TEST] Step 2 PASSED: Auto-discovered TCP connection verified.");

        } finally {
            beacon.stop();
            masterServerSocket.close();
        }

        System.out.println("\n=======================================================");
        System.out.println(">>> LAN AUTO-DISCOVERY TEST PASSED SUCCESSFULLY! <<<");
        System.out.println("=======================================================");
    }
}
