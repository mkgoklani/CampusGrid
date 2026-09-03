import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * INTEGRATION TEST: CRASH RECOVERY (STATE CHECKPOINTING) & RENDER ETA ESTIMATOR
 * 
 * Verifies:
 * 1. RenderETAEstimator computes accurate EWMA per-frame durations and remaining time.
 * 2. StateCheckpointManager serializes in-flight jobs and successfully recovers state 
 *    after simulated master node crash.
 */
public class CrashRecoveryAndETATest {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println(" TEST: RENDER ETA ESTIMATOR & STATE CHECKPOINTING");
        System.out.println("=================================================");

        testRenderETAEstimator();
        testStateCheckpointRecovery();

        System.out.println("\n=================================================");
        System.out.println(" ✔ ALL CRASH RECOVERY & ETA TESTS PASSED!");
        System.out.println("=================================================");
    }

    private static void testRenderETAEstimator() {
        System.out.println("\n[1/2] Testing RenderETAEstimator (EWMA & Remaining Time)...");

        RenderETAEstimator estimator = new RenderETAEstimator();
        String jobId = "JOB_TEST_ETA_01";
        int totalFrames = 100;

        estimator.initJob(jobId, totalFrames);
        estimator.updateActiveWorkers(jobId, 4); // 4 active nodes

        // Record 3 completed tasks with varying durations
        // Task 1: 5 frames in 10000ms (2000ms/frame)
        estimator.recordFrameCompletion(jobId, 5, 10000);
        // Task 2: 5 frames in 9000ms (1800ms/frame)
        estimator.recordFrameCompletion(jobId, 5, 9000);
        // Task 3: 5 frames in 11000ms (2200ms/frame)
        estimator.recordFrameCompletion(jobId, 5, 11000);

        RenderETAEstimator.ETASnapshot snapshot = estimator.getETA(jobId);
        assertNotNull(snapshot, "ETA snapshot must not be null");
        assertTrue(snapshot.completedFrames == 15, "Completed frames should be 15, got " + snapshot.completedFrames);
        assertTrue(snapshot.totalFrames == 100, "Total frames should be 100");
        assertTrue(snapshot.activeWorkers == 4, "Active workers should be 4");
        assertTrue(snapshot.avgFrameTimeMs > 1500 && snapshot.avgFrameTimeMs < 2500, 
            "Avg frame time should be ~2000ms, got " + snapshot.avgFrameTimeMs);
        assertTrue(snapshot.remainingMs > 0, "Remaining time should be positive");

        String formatted = snapshot.formatRemaining();
        assertNotNull(formatted, "Formatted remaining string must not be null");
        System.out.printf("  ✓ ETA Snapshot computed: 15/100 frames done. Est remaining: %s (Avg: %.1fms/frame, Workers: %d)%n",
            formatted, snapshot.avgFrameTimeMs, snapshot.activeWorkers);

        String json = snapshot.toJson();
        assertTrue(json.contains("\"remainingMs\""), "JSON must contain remainingMs");
        assertTrue(json.contains("\"avgFrameTimeMs\""), "JSON must contain avgFrameTimeMs");
        System.out.println("  ✓ ETA JSON serialization validated: " + json);
    }

    private static void testStateCheckpointRecovery() {
        System.out.println("\n[2/2] Testing StateCheckpointManager (Crash Recovery & Re-queuing)...");

        Path testDir = Paths.get("./data_test");
        try {
            Files.createDirectories(testDir);

            // 1. Setup simulated running job
            JobManager originalManager = new JobManager();
            Map<String, Object> params = new HashMap<>();
            params.put("blendFileName", "classroom.blend");
            params.put("renderEngine", "CYCLES");
            params.put("renderSamples", 128);

            Job activeJob = new Job("JOB_CRASH_TEST_99", "Classroom Benchmark", "BLENDER", 50, params);
            activeJob.sliceIntoFrameRanges(10); // 5 tasks of 10 frames
            originalManager.submitJob(activeJob, 10);

            // Simulate 2 tasks completing before crash
            Job.SubTask task1 = activeJob.pollPendingSubTask();
            activeJob.markSubTaskCompleted(task1.getTaskId());

            Job.SubTask task2 = activeJob.pollPendingSubTask();
            activeJob.markSubTaskCompleted(task2.getTaskId());

            assertTrue(activeJob.getCompletedTaskCount() == 2, "Should have 2 completed subtasks");

            // 2. Save Checkpoint to disk
            StateCheckpointManager checkpointMgr = new StateCheckpointManager(originalManager, testDir, 5000);
            checkpointMgr.saveCheckpoint();

            File checkpointFile = testDir.resolve("checkpoint.json").toFile();
            assertTrue(checkpointFile.exists() && checkpointFile.length() > 0, "Checkpoint file must exist on disk");
            System.out.printf("  ✓ Checkpoint successfully persisted to disk (%d bytes)%n", checkpointFile.length());

            // 3. Simulate JVM Crash: Create a completely fresh JobManager & restore
            JobManager recoveredManager = new JobManager();
            assertTrue(recoveredManager.getAllJobs().isEmpty(), "Recovered manager must initially be empty");

            StateCheckpointManager recoveryMgr = new StateCheckpointManager(recoveredManager, testDir, 5000);
            int restoredCount = recoveryMgr.restoreFromCheckpoint();

            assertTrue(restoredCount == 1, "Should have restored 1 job, got " + restoredCount);
            Job recoveredJob = recoveredManager.getJob("JOB_CRASH_TEST_99");
            assertNotNull(recoveredJob, "Recovered job must exist in JobManager");
            assertTrue(recoveredJob.getTotalFrames() == 50, "Total frames must be preserved");
            assertTrue(recoveredJob.getJobName().equals("Classroom Benchmark"), "Job name must be preserved");
            assertTrue("CYCLES".equals(recoveredJob.getParameters().get("renderEngine")), "Parameters must be preserved");

            // Verify that pending subtasks are ready to dispatch to new workers
            Job.SubTask pendingTask = recoveredManager.getNextPendingTask();
            assertNotNull(pendingTask, "Recovered manager should have pending tasks available for workers");
            System.out.printf("  ✓ Job successfully recovered after simulated crash: Job ID [%s], Next dispatchable task [%s] (Frames: %s)%n",
                recoveredJob.getJobId(), pendingTask.getTaskId(), pendingTask.getFrameRange());

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Crash recovery test failed: " + e.getMessage());
        } finally {
            // Clean up test directory
            try {
                File[] files = testDir.toFile().listFiles();
                if (files != null) for (File f : files) f.delete();
                testDir.toFile().delete();
            } catch (Exception ignored) {}
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            System.err.println("❌ ASSERTION FAILED: " + message);
            throw new AssertionError(message);
        }
    }

    private static void assertNotNull(Object obj, String message) {
        if (obj == null) {
            System.err.println("❌ ASSERTION FAILED: " + message);
            throw new AssertionError(message);
        }
    }
}
