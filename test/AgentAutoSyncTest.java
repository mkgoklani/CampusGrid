import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import com.campusgrid.core.*;

/**
 * Comprehensive Integration Test for:
 * 1. Master Node Agent Auto-Sync & Version Management (AgentVersionManager)
 * 2. Automatic on-demand packaging of agent.jar
 * 3. REST endpoints /api/agent/version, /download/agent.jar, and /api/agent/compile-sync
 * 4. Outdated Agent detection during Heartbeat / Handshake
 * 5. Autonomous hot-restarting update transmission
 */
public class AgentAutoSyncTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING AGENT AUTO-SYNC & AUTO-UPDATE INTEGRATION TEST ===");

        // 1. Initialize Master Node components
        WorkerRegistry workerRegistry = new WorkerRegistry();
        JobManager jobManager = new JobManager();
        BenchmarkManager benchmarkManager = new BenchmarkManager(workerRegistry);
        AgentVersionManager versionManager = new AgentVersionManager();
        versionManager.setCurrentVersion("1.0.1");
        versionManager.setCurrentBuild(101);

        System.out.println("\n--- Step 1: Testing Automated Agent JAR Packaging ---");
        File testJar = new File("test-agent.jar");
        boolean packaged = versionManager.packageAgentJar(testJar);
        assert packaged : "Failed to package test-agent.jar";
        assert testJar.exists() && testJar.length() > 1000 : "Packaged JAR is missing or empty";

        // Verify Manifest
        try (JarFile jf = new JarFile(testJar)) {
            String mainClass = jf.getManifest().getMainAttributes().getValue("Main-Class");
            String versionAttr = jf.getManifest().getMainAttributes().getValue("Agent-Version");
            System.out.printf("[TEST] Packaged JAR Main-Class: %s, Version: %s\n", mainClass, versionAttr);
            assert "com.campusgrid.agent.Agent".equals(mainClass) : "Incorrect Main-Class in Manifest";
            assert "1.0.1".equals(versionAttr) : "Incorrect Version in Manifest";
        }
        testJar.delete();
        System.out.println("[TEST] Step 1 PASSED: Agent JAR packaging verified.");

        // 2. Start Dashboard Server and test REST endpoints
        System.out.println("\n--- Step 2: Testing Dashboard REST API Endpoints ---");
        int httpPort = 8093;
        int wsPort = 8094;
        DashboardServer server = new DashboardServer(jobManager, workerRegistry, benchmarkManager, versionManager, httpPort, wsPort);
        server.start();

        try {
            // Test GET /api/agent/version
            URL verUrl = new URL("http://localhost:" + httpPort + "/api/agent/version");
            HttpURLConnection verConn = (HttpURLConnection) verUrl.openConnection();
            int verCode = verConn.getResponseCode();
            String verJson = new String(verConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] GET /api/agent/version response: " + verJson);
            assert verCode == 200 : "Expected 200 from /api/agent/version";
            assert verJson.contains("\"version\":\"1.0.1\"") : "Missing version in response";
            assert verJson.contains("\"build\":101") : "Missing build in response";

            // Test GET /download/agent.jar
            URL downloadUrl = new URL("http://localhost:" + httpPort + "/download/agent.jar");
            HttpURLConnection dlConn = (HttpURLConnection) downloadUrl.openConnection();
            int dlCode = dlConn.getResponseCode();
            byte[] jarBytes = dlConn.getInputStream().readAllBytes();
            System.out.printf("[TEST] GET /download/agent.jar -> HTTP %d, downloaded %d bytes\n", dlCode, jarBytes.length);
            assert dlCode == 200 : "Expected 200 from /download/agent.jar";
            assert jarBytes.length > 1000 : "Downloaded agent.jar is too small";

            // 3. Test Outdated Worker Detection and UPDATE_AGENT Trigger
            System.out.println("\n--- Step 3: Outdated Worker Detection & Trigger ---");
            // Register a mock worker running old version v1.0.0 (Build 100)
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos);
            ObjectOutputStream mockWorkerOut = new ObjectOutputStream(pos);
            mockWorkerOut.flush();
            ObjectInputStream mockWorkerIn = new ObjectInputStream(pis);

            WorkerState outdatedWorker = new WorkerState("agent-lab-pc-01", "192.168.1.15", null, mockWorkerOut);
            outdatedWorker.setAgentVersion("1.0.0");
            outdatedWorker.setAgentBuildNumber(100);
            workerRegistry.registerWorker(outdatedWorker);

            // Check version manager
            boolean isOutdated = versionManager.isAgentOutdated(outdatedWorker.getAgentVersion(), outdatedWorker.getAgentBuildNumber());
            System.out.printf("[TEST] Is Worker [%s] (v%s, b%d) Outdated vs Master (v%s, b%d)? %b\n",
                outdatedWorker.getWorkerId(), outdatedWorker.getAgentVersion(), outdatedWorker.getAgentBuildNumber(),
                versionManager.getCurrentVersion(), versionManager.getCurrentBuild(), isOutdated);
            assert isOutdated : "Worker with v1.0.0-b100 should be detected as outdated";

            // Test POST /api/agent/compile-sync
            URL syncUrl = new URL("http://localhost:" + httpPort + "/api/agent/compile-sync");
            HttpURLConnection syncConn = (HttpURLConnection) syncUrl.openConnection();
            syncConn.setRequestMethod("POST");
            int syncCode = syncConn.getResponseCode();
            String syncJson = new String(syncConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] POST /api/agent/compile-sync response: " + syncJson);
            assert syncCode == 200 : "Expected 200 from /api/agent/compile-sync";
            assert syncJson.contains("\"success\":true") : "Expected success:true in compile-sync response";
            assert syncJson.contains("\"notifiedWorkers\":1") : "Expected 1 worker to be notified";

            // Verify the outdated worker received the UPDATE_AGENT directive across the wire
            Object receivedMsg1 = mockWorkerIn.readObject();
            Object receivedMsg2 = mockWorkerIn.readObject();
            System.out.println("[TEST] Worker received packet 1: " + receivedMsg1);
            System.out.println("[TEST] Worker received packet 2: " + receivedMsg2);

            assert receivedMsg1 instanceof GridMessage : "Packet 1 should be GridMessage";
            GridMessage gm = (GridMessage) receivedMsg1;
            assert gm.getType() == MessageType.UPDATE_AGENT : "GridMessage type should be UPDATE_AGENT";
            assert receivedMsg2 instanceof String && ((String) receivedMsg2).startsWith("UPDATE_AGENT:") : "Packet 2 should be UPDATE_AGENT string";

            System.out.println("[TEST] Step 3 PASSED: Outdated worker detected and UPDATE_AGENT directive received.");

            // 4. Simulate Worker Reconnect with Updated Version
            System.out.println("\n--- Step 4: Worker Reconnect with Updated Version ---");
            outdatedWorker.setAgentVersion(versionManager.getCurrentVersion());
            outdatedWorker.setAgentBuildNumber(versionManager.getCurrentBuild());

            boolean isOutdatedAfterUpdate = versionManager.isAgentOutdated(outdatedWorker.getAgentVersion(), outdatedWorker.getAgentBuildNumber());
            System.out.printf("[TEST] Is Worker [%s] (v%s, b%d) Outdated after update? %b\n",
                outdatedWorker.getWorkerId(), outdatedWorker.getAgentVersion(), outdatedWorker.getAgentBuildNumber(), isOutdatedAfterUpdate);
            assert !isOutdatedAfterUpdate : "Updated worker should no longer be marked outdated";

            System.out.println("[TEST] Step 4 PASSED: Updated worker verified up-to-date.");

        } finally {
            server.stop();
        }

        System.out.println("\n=======================================================");
        System.out.println(">>> AGENT AUTO-SYNC & AUTO-UPDATE TEST PASSED <<<");
        System.out.println("=======================================================");
    }
}
