import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.campusgrid.core.*;
import com.campusgrid.agent.blender.BlenderJobExecutor;

/**
 * Comprehensive System Resilience, Error-Handling & Edge-Case Integration Test Suite.
 */
public class SystemResilienceAndEdgeCasesTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING SYSTEM RESILIENCE & EDGE-CASES TEST SUITE ===");

        // 1. Test Telemetry Bounds & NaN/Infinity Defenses
        System.out.println("\n--- Step 1: Telemetry Bounds & NaN/Infinity Defense ---");
        HeartbeatPayload nanPayload = new HeartbeatPayload(
            999,               // Out-of-bounds temp > 150°C
            Double.NaN,        // NaN RAM
            null,              // Null status
            Double.POSITIVE_INFINITY, // Infinite CPU
            null, null, null, null, null, true, true,
            null, -50          // Null version & negative build
        );
        assert nanPayload.getCpuTemperature() == 150 : "Temp should be clamped to 150°C max";
        assert nanPayload.getRamUsagePercent() == 0.0 : "NaN RAM should be normalized to 0.0%";
        assert nanPayload.getCpuUsagePercent() == 0.0 : "Infinite CPU should be normalized to 0.0%";
        assert nanPayload.getStatus() == WorkerStatus.IDLE : "Null status should default to IDLE";
        assert "1.0.0".equals(nanPayload.getAgentVersion()) : "Null version should default to 1.0.0";
        assert nanPayload.getAgentBuildNumber() == 1 : "Negative build should clamp to 1";
        System.out.println("[TEST] Step 1 PASSED: Telemetry bounds and NaN protection verified.");

        // 2. Test Division-by-Zero in Benchmark Engine
        System.out.println("\n--- Step 2: Division-by-Zero in Benchmark Engine ---");
        WorkerRegistry workerRegistry = new WorkerRegistry();
        JobManager jobManager = new JobManager();
        BenchmarkManager benchmarkManager = new BenchmarkManager(workerRegistry);

        Job zeroJob = new Job("ZERO_JOB", "Zero Job", "BLENDER", 0, new HashMap<>());
        jobManager.submitJob(zeroJob, 1);
        Job.SubTask zeroTask = zeroJob.getSubTasks().iterator().next();
        jobManager.updateJobProgress("ZERO_JOB", zeroTask.getTaskId(), true);

        // Record benchmark with 0 frames and zero elapsed time
        BenchmarkManager.JobBenchmarkRecord zeroRecord = benchmarkManager.recordJobCompletion(zeroJob);
        assert zeroRecord != null : "Record should not be null";
        assert !Double.isNaN(zeroRecord.speedupMultiplier) : "Speedup should not be NaN";
        assert !Double.isNaN(zeroRecord.parallelEfficiencyPct) : "Efficiency should not be NaN";
        assert !Double.isNaN(zeroRecord.framesPerMinute) : "FPS should not be NaN";

        String compJson = benchmarkManager.generateComparisonJson("ZERO_JOB");
        assert compJson.contains("\"hasData\":true") : "Comparison JSON should be valid";
        System.out.println("[TEST] Step 2 PASSED: Zero-frame and zero-time benchmark calculations verified.");

        // 3. Test REST API Security, Path Traversal Defense & Zero Frame Submission
        System.out.println("\n--- Step 3: REST API Security & Path Traversal Defenses ---");
        int httpPort = 8099;
        int wsPort = 8100;
        DashboardServer server = new DashboardServer(jobManager, workerRegistry, benchmarkManager, new AgentVersionManager(), httpPort, wsPort);
        server.start();

        try {
            // Test 3.1: Directory Traversal Attempt on /output/
            URL attackUrl = new URL("http://localhost:" + httpPort + "/output/../../secret.txt");
            HttpURLConnection attackConn = (HttpURLConnection) attackUrl.openConnection();
            int attackCode = attackConn.getResponseCode();
            System.out.println("[TEST] Traversal attack GET /output/../../secret.txt -> HTTP " + attackCode);
            assert attackCode == 400 || attackCode == 403 || attackCode == 404 : "Path traversal should be rejected (received: " + attackCode + ")";

            // Test 3.2: Malicious jobId with traversal characters in /api/jobs/frames
            URL malJobUrl = new URL("http://localhost:" + httpPort + "/api/jobs/frames?jobId=../../etc");
            HttpURLConnection malJobConn = (HttpURLConnection) malJobUrl.openConnection();
            int malJobCode = malJobConn.getResponseCode();
            System.out.println("[TEST] Malicious jobId GET /api/jobs/frames?jobId=../../etc -> HTTP " + malJobCode);
            assert malJobCode == 400 : "Malicious jobId should return 400 Bad Request";

            // Test 3.3: Submit job with negative frame count
            URL submitUrl = new URL("http://localhost:" + httpPort + "/api/jobs/submit");
            HttpURLConnection submitConn = (HttpURLConnection) submitUrl.openConnection();
            submitConn.setRequestMethod("POST");
            submitConn.setDoOutput(true);
            submitConn.getOutputStream().write("{\"jobName\":\"Resilience Test\",\"totalFrames\":-50,\"blendFilePath\":\"scene.blend\"}".getBytes(StandardCharsets.UTF_8));
            int submitCode = submitConn.getResponseCode();
            String submitResp = new String(submitConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] Submit negative frame job -> HTTP " + submitCode + " Body: " + submitResp);
            assert submitCode == 201 : "Job submission should safely normalize frames";
            assert submitResp.contains("\"success\":true") : "Expected success:true in response";

            System.out.println("[TEST] Step 3 PASSED: REST API security and input validation verified.");

        } finally {
            server.stop();
        }

        // 4. Test Worker Mid-Execution Disconnect & Automatic SubTask Re-queueing
        System.out.println("\n--- Step 4: Worker Sudden Disconnect & Task Failover ---");
        Job failoverJob = new Job("JOB_FAILOVER_TEST", "Failover Job", "BLENDER", 10, new HashMap<>());
        jobManager.submitJob(failoverJob, 5);

        Job.SubTask failoverSubTask = failoverJob.getSubTasks().iterator().next();
        WorkerState failingWorker = new WorkerState("failing-worker-01", "192.168.1.99", null, null);
        failingWorker.setStatus(WorkerStatus.BUSY);
        failingWorker.setCurrentJobId("JOB_FAILOVER_TEST");
        failingWorker.setCurrentTaskId(failoverSubTask.getTaskId());
        failoverSubTask.setStatus(Job.SubTaskStatus.DISPATCHED);
        failoverSubTask.setAssignedWorkerId("failing-worker-01");
        workerRegistry.registerWorker(failingWorker);

        // Simulate sudden disconnection failure
        workerRegistry.handleWorkerFailure("failing-worker-01", jobManager);

        // Check that worker is marked OFFLINE and SubTask is safely re-queued to PENDING
        assert failingWorker.getStatus() == WorkerStatus.OFFLINE : "Worker should be OFFLINE";
        assert failoverSubTask.getStatus() == Job.SubTaskStatus.PENDING : "SubTask should be re-queued to PENDING";
        assert failoverSubTask.getAssignedWorkerId() == null : "Assigned worker should be cleared";
        System.out.println("[TEST] Step 4 PASSED: Disconnect failover and task re-queueing verified.");

        // 5. Test Multi-Tier Compute Fallback (Software Frame Generator)
        System.out.println("\n--- Step 5: Pure Java Software Frame Generator Fallback ---");
        Path testDir = Paths.get("render_output/resilience_frames");
        Files.createDirectories(testDir);

        List<String> softwareFrames = BlenderJobExecutor.executeJob(
            "JOB_RESILIENCE_FALLBACK",
            "nonexistent_missing.blend",
            1, 2,
            testDir.toString(),
            "CYCLES",
            true, // useGpu = true with missing Blender triggers software renderer
            null
        );

        assert softwareFrames != null && softwareFrames.size() == 2 : "Software fallback should generate 2 frames";
        for (String fPath : softwareFrames) {
            File f = new File(fPath);
            assert f.exists() && f.length() > 500 : "Generated software frame is missing or empty";
        }
        System.out.println("[TEST] Step 5 PASSED: Multi-tier compute fallback verified.");

        // Clean up test frames
        for (String fPath : softwareFrames) {
            new File(fPath).delete();
        }
        testDir.toFile().delete();

        System.out.println("\n=======================================================");
        System.out.println(">>> ALL SYSTEM RESILIENCE & EDGE-CASE TESTS PASSED <<<");
        System.out.println("=======================================================");
    }
}
