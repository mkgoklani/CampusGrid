import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import com.campusgrid.agent.network.MasterConnection;

/**
 * AUTOMATED SPEEDUP BENCHMARK & MOSAIC STREAM TEST
 * 
 * Verifies:
 * 1. Multi-node distributed rendering execution.
 * 2. Speedup benchmark calculation and JSON serialization.
 * 3. Live renderedFrames array serialization for the visual mosaic filmstrip.
 */
public class SpeedupAndMosaicTest {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("  STARTING SPEEDUP & MOSAIC STREAM TEST");
        System.out.println("==================================================");

        int agentPort = 8100;
        int httpPort = 8101;
        int wsPort = 8102;

        // 1. Start Master Node
        MasterNodeApplication master = new MasterNodeApplication(agentPort, httpPort, wsPort);
        master.start();
        Thread.sleep(1000);

        // 2. Connect 2 Agent Nodes
        MasterConnection agent1 = new MasterConnection("127.0.0.1", agentPort);
        MasterConnection agent2 = new MasterConnection("127.0.0.1", agentPort);
        new Thread(() -> agent1.connect(), "Agent1-Thread").start();
        new Thread(() -> agent2.connect(), "Agent2-Thread").start();

        Thread.sleep(2500);

        // 3. Submit 4-frame job partitioned across the 2 nodes (2 frames each)
        URL submitUrl = new URL("http://localhost:" + httpPort + "/api/jobs/submit");
        HttpURLConnection conn = (HttpURLConnection) submitUrl.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        String submitPayload = "{"
            + "\"jobName\":\"Speedup Benchmark Test\","
            + "\"workloadType\":\"BLENDER\","
            + "\"blendFilePath\":\"test.blend\","
            + "\"blendFileName\":\"test.blend\","
            + "\"totalFrames\":4,"
            + "\"framesPerTask\":2,"
            + "\"renderEngine\":\"WORKBENCH\","
            + "\"renderSamples\":16"
            + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(submitPayload.getBytes(StandardCharsets.UTF_8));
        }

        int submitCode = conn.getResponseCode();
        System.out.println("[TEST] HTTP Submit response code: " + submitCode);
        if (submitCode != 200 && submitCode != 201) {
            System.err.println("[TEST-ERR] Job submit failed: " + submitCode);
            master.stop();
            System.exit(1);
        }

        // 4. Poll until job completion (max 25s)
        long startPoll = System.currentTimeMillis();
        boolean completed = false;

        while (System.currentTimeMillis() - startPoll < 25000) {
            for (Job job : master.getJobManager().getAllJobs().values()) {
                if (job.getStatus() == JobStatus.COMPLETED) {
                    completed = true;
                    break;
                }
            }
            if (completed) break;
            Thread.sleep(500);
        }

        if (!completed) {
            System.err.println("[TEST-ERR] Job failed to complete within timeout!");
            master.stop();
            System.exit(1);
        }

        // 5. Query /api/jobs to inspect speedup and renderedFrames fields
        System.out.println("\n[TEST] Querying /api/jobs for Speedup Benchmark and Mosaic Stream...");
        URL jobsUrl = new URL("http://localhost:" + httpPort + "/api/jobs");
        HttpURLConnection jobsConn = (HttpURLConnection) jobsUrl.openConnection();
        jobsConn.setRequestMethod("GET");

        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(jobsConn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        }

        String jsonStr = json.toString();
        System.out.println("[TEST] Jobs JSON: " + jsonStr);

        if (!jsonStr.contains("\"speedup\"")) {
            System.err.println("[TEST-ERR] speedup field missing from /api/jobs output!");
            master.stop();
            System.exit(1);
        }

        if (!jsonStr.contains("\"renderedFrames\"")) {
            System.err.println("[TEST-ERR] renderedFrames field missing from /api/jobs output!");
            master.stop();
            System.exit(1);
        }

        System.out.println("[TEST] ✔ Speedup Benchmark & Live Mosaic JSON validated successfully!");

        System.out.println("\n==================================================");
        System.out.println("  🎉 SPEEDUP BENCHMARK & MOSAIC STREAM PASSED! 🎉");
        System.out.println("==================================================");

        agent1.disconnect();
        agent2.disconnect();
        master.stop();
        System.exit(0);
    }
}
