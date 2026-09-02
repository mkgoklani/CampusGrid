import com.campusgrid.agent.Agent;
import com.campusgrid.agent.network.MasterConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Phase2PipelineTest {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("  PHASE 2 DISTRIBUTED BLENDER PIPELINE TEST");
        System.out.println("==================================================");

        int agentPort = 8090;
        int httpPort = 8091;
        int wsPort = 8092;

        // 1. Start Master Node
        System.out.println("[TEST] Starting MasterNodeApplication on ports 8090/8091/8092...");
        MasterNodeApplication master = new MasterNodeApplication(agentPort, httpPort, wsPort);
        master.start();

        Thread.sleep(1000);

        // 2. Start Agent 1
        System.out.println("[TEST] Connecting Agent 1...");
        MasterConnection agentConn1 = new MasterConnection("127.0.0.1", agentPort);
        new Thread(() -> agentConn1.connect(), "Agent-1-Thread").start();

        // 3. Start Agent 2
        System.out.println("[TEST] Connecting Agent 2...");
        MasterConnection agentConn2 = new MasterConnection("127.0.0.1", agentPort);
        new Thread(() -> agentConn2.connect(), "Agent-2-Thread").start();

        // Wait for workers to connect and send initial telemetry
        Thread.sleep(2500);

        int totalWorkers = master.getWorkerRegistry().getAllWorkers().size();
        System.out.println("[TEST] Connected workers in registry: " + totalWorkers);
        if (totalWorkers < 2) {
            System.err.println("[TEST-FAIL] Expected at least 2 workers connected, found: " + totalWorkers);
            master.stop();
            System.exit(1);
        }

        // 4. Submit a 10-frame distributed render job via HTTP REST API
        System.out.println("[TEST] Submitting 10-frame distributed render job via REST API...");
        URL submitUrl = new URL("http://localhost:" + httpPort + "/api/jobs/submit");
        HttpURLConnection conn = (HttpURLConnection) submitUrl.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        String submitPayload = "{"
            + "\"jobName\":\"Distributed Test Render\","
            + "\"workloadType\":\"BLENDER\","
            + "\"blendFilePath\":\"test.blend\","
            + "\"blendFileName\":\"test.blend\","
            + "\"totalFrames\":4,"
            + "\"framesPerTask\":2,"
            + "\"cleanUpFrames\":false,"
            + "\"renderEngine\":\"WORKBENCH\""
            + "}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(submitPayload.getBytes(StandardCharsets.UTF_8));
        }

        int respCode = conn.getResponseCode();
        String respBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        System.out.printf("[TEST] Submit Response (%d): %s\n", respCode, respBody);

        String jobId = null;
        int idx = respBody.indexOf("\"jobId\":\"");
        if (idx != -1) {
            int start = idx + 9;
            int end = respBody.indexOf("\"", start);
            jobId = respBody.substring(start, end);
        }

        if (jobId == null) {
            System.err.println("[TEST-FAIL] Could not extract jobId from response!");
            master.stop();
            System.exit(1);
        }

        System.out.println("[TEST] Tracking Job: " + jobId);

        // 5. Poll for completion
        boolean completed = false;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(1000);
            Job job = master.getJobManager().getAllJobs().get(jobId);
            if (job != null) {
                System.out.printf("[TEST-POLL %02ds] Job [%s] Status: %s (Progress: %.1f%%, Slices: %d/%d)\n",
                    i, jobId, job.getStatus(), job.getProgressPercentage(), job.getCompletedTaskCount(), job.getSubTaskCount());

                if (job.getStatus() == JobStatus.COMPLETED) {
                    completed = true;
                    break;
                }
            }
        }

        if (!completed) {
            System.err.println("[TEST-FAIL] Job did not reach COMPLETED status within timeout.");
            master.stop();
            System.exit(1);
        }

        // 6. Verify Output Files on Disk
        File outputDir = new File("./output/" + jobId);
        System.out.println("[TEST] Inspecting output directory: " + outputDir.getAbsolutePath());
        if (!outputDir.exists() || !outputDir.isDirectory()) {
            System.err.println("[TEST-FAIL] Output directory does not exist!");
            master.stop();
            System.exit(1);
        }

        File[] pngFrames = outputDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        int pngCount = pngFrames != null ? pngFrames.length : 0;
        System.out.println("[TEST] Total PNG frames collected: " + pngCount);

        File zipFile = new File(outputDir, jobId + "_all_frames.zip");
        System.out.printf("[TEST] ZIP bundle [%s]: exists=%b, size=%d bytes\n",
            zipFile.getName(), zipFile.exists(), zipFile.length());

        if (pngCount < 4) {
            System.err.printf("[TEST-WARN] Expected 4 PNG frames, found: %d\n", pngCount);
        }

        if (!zipFile.exists() || zipFile.length() == 0) {
            System.err.println("[TEST-FAIL] ZIP bundle was not created or is empty!");
            master.stop();
            System.exit(1);
        }

        // 7. Verify HTTP Output File Download Endpoint
        URL zipDownloadUrl = new URL("http://localhost:" + httpPort + "/output/" + jobId + "/" + jobId + "_all_frames.zip");
        HttpURLConnection zipConn = (HttpURLConnection) zipDownloadUrl.openConnection();
        int zipHttpCode = zipConn.getResponseCode();
        long zipLength = zipConn.getContentLengthLong();
        System.out.printf("[TEST] HTTP GET ZIP Download (%d): %d bytes, Content-Type=%s\n",
            zipHttpCode, zipLength, zipConn.getContentType());

        if (zipHttpCode != 200 || zipLength <= 0) {
            System.err.println("[TEST-FAIL] Failed downloading ZIP bundle via HTTP!");
            master.stop();
            System.exit(1);
        }

        System.out.println("\n==================================================");
        System.out.println("  ✔✔✔ PHASE 2 DISTRIBUTED PIPELINE TEST PASSED! ✔✔✔");
        System.out.println("==================================================");

        // Teardown
        agentConn1.disconnect();
        agentConn2.disconnect();
        master.stop();
        System.exit(0);
    }
}
