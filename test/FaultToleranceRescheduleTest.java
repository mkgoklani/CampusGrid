import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import com.campusgrid.agent.network.MasterConnection;

/**
 * AUTOMATED FAULT-TOLERANCE & AUTO-RESCHEDULING TEST
 * 
 * Verifies that when a worker node abruptly crashes/disconnects mid-render:
 * 1. The Master detects the socket loss / failure.
 * 2. The in-flight sub-task slice is automatically rescued and re-queued.
 * 3. The remaining active worker picks up the orphaned slice.
 * 4. The full job successfully completes 100% of frames!
 */
public class FaultToleranceRescheduleTest {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("  STARTING FAULT-TOLERANCE RESCHEDULING TEST");
        System.out.println("==================================================");

        int agentPort = 8094;
        int httpPort = 8095;
        int wsPort = 8096;

        // 1. Initialize Master Node
        MasterNodeApplication master = new MasterNodeApplication(agentPort, httpPort, wsPort);
        master.start();
        Thread.sleep(1000);

        // 2. Connect 2 Agent Worker Nodes
        System.out.println("[TEST] Connecting Agent 1...");
        MasterConnection agentConn1 = new MasterConnection("127.0.0.1", agentPort);
        Thread t1 = new Thread(() -> agentConn1.connect(), "Agent-1-Thread");
        t1.start();

        System.out.println("[TEST] Connecting Agent 2...");
        MasterConnection agentConn2 = new MasterConnection("127.0.0.1", agentPort);
        Thread t2 = new Thread(() -> agentConn2.connect(), "Agent-2-Thread");
        t2.start();

        Thread.sleep(2500);

        int totalWorkers = master.getWorkerRegistry().getAllWorkers().size();
        System.out.println("[TEST] Connected workers in registry: " + totalWorkers);
        if (totalWorkers < 2) {
            System.err.println("[TEST-FAIL] Expected at least 2 workers connected, found: " + totalWorkers);
            master.stop();
            System.exit(1);
        }

        // 3. Submit a 4-frame job with 2 frames per task (2 slices: Task 1: 1-2, Task 2: 3-4)
        System.out.println("[TEST] Submitting 4-frame job partitioned into 2 slices...");
        URL submitUrl = new URL("http://localhost:" + httpPort + "/api/jobs/submit");
        HttpURLConnection conn = (HttpURLConnection) submitUrl.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        String submitPayload = "{"
            + "\"jobName\":\"Fault Tolerance Self-Healing Test\","
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
            System.err.println("[TEST-FAIL] Failed to submit test job: HTTP " + submitCode);
            master.stop();
            System.exit(1);
        }

        // 4. Wait for tasks to dispatch, then abruptly kill Agent 1 mid-render!
        Thread.sleep(800);
        System.out.println("\n[TEST] 💥 SIMULATING SUDDEN AGENT 1 CRASH / DISCONNECT 💥");
        agentConn1.disconnect();
        t1.interrupt();

        System.out.println("[TEST] Agent 1 disconnected. Monitoring Master Node self-healing recovery...");

        // 5. Poll until job completion or timeout (max 30s)
        long startPoll = System.currentTimeMillis();
        boolean completed = false;
        String activeJobId = null;

        while (System.currentTimeMillis() - startPoll < 30000) {
            for (Job job : master.getJobManager().getAllJobs().values()) {
                activeJobId = job.getJobId();
                if (job.getStatus() == JobStatus.COMPLETED) {
                    completed = true;
                    break;
                }
            }
            if (completed) break;
            Thread.sleep(600);
        }

        if (!completed) {
            System.err.println("[TEST-ERR] Job failed to complete after worker failure!");
            agentConn2.disconnect();
            master.stop();
            System.exit(1);
        }

        System.out.println("\n[TEST] ✔ Job fully completed after self-healing auto-rescheduling!");

        // 6. Verify that all 4 PNG frames exist on disk
        File outDir = new File("./output/" + activeJobId);
        File[] frames = outDir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        int frameCount = frames != null ? frames.length : 0;
        System.out.printf("[TEST] Output frames collected on Master: %d / 4\n", frameCount);

        if (frameCount != 4) {
            System.err.printf("[TEST-ERR] Expected 4 frames, found %d\n", frameCount);
            agentConn2.disconnect();
            master.stop();
            System.exit(1);
        }

        System.out.println("\n==================================================");
        System.out.println("  🎉 FAULT-TOLERANCE & AUTO-RESCHEDULING PASSED! 🎉");
        System.out.println("==================================================");

        agentConn2.disconnect();
        master.stop();
        System.exit(0);
    }
}
