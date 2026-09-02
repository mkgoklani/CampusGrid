import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import com.campusgrid.agent.network.LanDiscoveryClient;
import com.campusgrid.agent.network.MasterConnection;
import com.campusgrid.agent.os.GpuDetector;

/**
 * AUTOMATED GPU DETECTION & LAN AUTO-DISCOVERY TEST
 * 
 * Verifies:
 * 1. Master UDP beacon & LanDiscoveryClient zero-config IP discovery.
 * 2. Hardware GPU detection (NVIDIA OptiX / CUDA / AMD HIP / Metal).
 * 3. GPU telemetry reporting over WebSocket/REST cluster API.
 */
public class GpuAndLanDiscoveryTest {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("  STARTING GPU & LAN AUTO-DISCOVERY TEST");
        System.out.println("==================================================");

        int agentPort = 8097;
        int httpPort = 8098;
        int wsPort = 8099;

        // 1. Start Master Node with LAN Discovery active
        System.out.println("[TEST] Starting MasterNodeApplication on ports " + agentPort + "/" + httpPort + "/" + wsPort + "...");
        MasterNodeApplication master = new MasterNodeApplication(agentPort, httpPort, wsPort);
        master.start();

        Thread.sleep(1000);

        // 2. Test LAN Auto-Discovery Client
        System.out.println("\n[TEST] 📡 Scanning LAN via UDP broadcast for Master Node...");
        InetSocketAddress discoveredMaster = LanDiscoveryClient.discoverMaster(3000);
        System.out.println("[TEST] Discovery Result: " + discoveredMaster);

        if (discoveredMaster == null) {
            System.err.println("[TEST-ERR] LanDiscoveryClient failed to find Master beacon!");
            master.stop();
            System.exit(1);
        }
        System.out.printf("[TEST] ✔ Auto-discovered Master at: %s:%d\n", discoveredMaster.getHostString(), discoveredMaster.getPort());

        // 3. Test GPU Detection on Host
        System.out.println("\n[TEST] 🎮 Detecting GPU Compute Hardware...");
        String gpuInfo = GpuDetector.getGpuInfo();
        System.out.println("[TEST] Detected Hardware Device: " + gpuInfo);

        if (gpuInfo == null || gpuInfo.isEmpty()) {
            System.err.println("[TEST-ERR] GpuDetector returned null or empty info");
            master.stop();
            System.exit(1);
        }
        System.out.println("[TEST] ✔ GPU Detection verified successfully: " + gpuInfo);

        // 4. Connect Agent via Discovered Address
        System.out.println("\n[TEST] Connecting Agent using discovered parameters...");
        MasterConnection agentConn = new MasterConnection(discoveredMaster.getHostString(), discoveredMaster.getPort());
        Thread agentThread = new Thread(() -> agentConn.connect(), "Agent-Test-Thread");
        agentThread.start();

        // Wait for connection and initial telemetry packet
        Thread.sleep(2500);

        // 5. Query REST API to verify GPU is reflected in Cluster Telemetry
        System.out.println("\n[TEST] Querying REST API for worker telemetry & GPU status...");
        URL statusUrl = new URL("http://localhost:" + httpPort + "/api/cluster/status");
        HttpURLConnection conn = (HttpURLConnection) statusUrl.openConnection();
        conn.setRequestMethod("GET");

        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        }

        String jsonStr = json.toString();
        System.out.println("[TEST] Cluster Status JSON: " + jsonStr);

        if (!jsonStr.contains("gpuName")) {
            System.err.println("[TEST-ERR] gpuName field missing from cluster status JSON!");
            agentConn.disconnect();
            master.stop();
            System.exit(1);
        }

        System.out.println("[TEST] ✔ REST API confirmed active worker with GPU telemetry!");

        System.out.println("\n==================================================");
        System.out.println("  🎉 GPU & LAN AUTO-DISCOVERY TEST PASSED! 🎉");
        System.out.println("==================================================");

        agentConn.disconnect();
        master.stop();
        System.exit(0);
    }
}
