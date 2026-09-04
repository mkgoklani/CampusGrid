
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * AUTOMATED RESUME CHECKPOINT & FRAME FAST-FORWARD TEST
 * 
 * Verifies that:
 * 1. An interrupted/paused job with partial on-disk frames fast-forwards SubTasks
 *    to the first unrendered frame.
 * 2. Frames already completed on disk are preserved.
 * 3. If all frames are present, the SubTask is immediately marked COMPLETED.
 */
public class ResumeCheckpointTest {

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("  STARTING RESUME CHECKPOINT & FAST-FORWARD TEST");
        System.out.println("==================================================");

        String jobId = "JOB_TEST_RESUME_" + System.currentTimeMillis();
        File outDir = new File("./output/" + jobId);
        outDir.mkdirs();

        try {
            // 1. Create simulated rendered frames 1 through 49 on disk (mock PNG files)
            for (int f = 1; f <= 49; f++) {
                File frameFile = new File(outDir, String.format("frame_%04d.png", f));
                try (FileOutputStream fos = new FileOutputStream(frameFile)) {
                    fos.write(new byte[]{ (byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
                }
            }
            System.out.println("[TEST] Created mock frames 1..49 in " + outDir.getPath());

            // 2. Instantiate Job with 50 frames
            Map<String, Object> params = new HashMap<>();
            params.put("framesPerTask", 50);
            Job job = new Job(jobId, "Resume Verification Job", "BLENDER", 50, params);
            job.sliceIntoFrameRanges(50);

            Job.SubTask st = job.getSubTasks().iterator().next();
            System.out.printf("[TEST] Initial SubTask: %s (Start: %d, End: %d, Range: %s)\n",
                st.getTaskId(), st.getStartFrame(), st.getEndFrame(), st.getFrameRange());

            if (st.getStartFrame() != 1 || st.getEndFrame() != 50) {
                throw new AssertionError("Expected initial frames 1-50, got: " + st.getFrameRange());
            }

            // 3. Test getFirstMissingFrame
            int firstMissing = job.getFirstMissingFrame(st);
            System.out.println("[TEST] Detected first missing frame: " + firstMissing);
            if (firstMissing != 50) {
                throw new AssertionError("Expected first missing frame to be 50, got: " + firstMissing);
            }

            // 4. Test requeueSubTask fast-forward
            job.requeueSubTask(st, false);
            System.out.printf("[TEST] After requeue: SubTask %s (Start: %d, End: %d, Range: %s, Status: %s)\n",
                st.getTaskId(), st.getStartFrame(), st.getEndFrame(), st.getFrameRange(), st.getStatus());

            if (st.getStartFrame() != 50) {
                throw new AssertionError("Expected startFrame to be fast-forwarded to 50, got: " + st.getStartFrame());
            }
            if (!"50".equals(st.getFrameRange())) {
                throw new AssertionError("Expected frameRange to be '50', got: " + st.getFrameRange());
            }

            // 5. Simulate frame 50 completion and verify all-covered completion
            File frame50 = new File(outDir, "frame_0050.png");
            try (FileOutputStream fos = new FileOutputStream(frame50)) {
                fos.write(new byte[]{ (byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
            }

            int nextMissing = job.getFirstMissingFrame(st);
            System.out.println("[TEST] After frame 50 written, next missing frame: " + nextMissing);
            if (nextMissing <= 50) {
                throw new AssertionError("Expected next missing frame > 50, got: " + nextMissing);
            }

            job.requeueSubTask(st, false);
            System.out.println("[TEST] SubTask status after all frames present: " + st.getStatus());
            if (st.getStatus() != Job.SubTaskStatus.COMPLETED) {
                throw new AssertionError("Expected SubTask to be COMPLETED when all frames are on disk, got: " + st.getStatus());
            }

            // 6. Test JobManager.resumeJob() integration
            JobManager jm = new JobManager();
            String job2Id = "JOB_TEST_RESUME2_" + System.currentTimeMillis();
            File outDir2 = new File("./output/" + job2Id);
            outDir2.mkdirs();
            for (int f = 1; f <= 30; f++) {
                File frameFile = new File(outDir2, String.format("frame_%04d.png", f));
                try (FileOutputStream fos = new FileOutputStream(frameFile)) {
                    fos.write(new byte[]{ (byte)0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
                }
            }
            Job job2 = new Job(job2Id, "Resume Verification Job 2", "BLENDER", 50, params);
            job2.sliceIntoFrameRanges(50);
            job2.setStatus(JobStatus.PAUSED);
            jm.registerJob(job2);

            boolean resumed = jm.resumeJob(job2Id);
            System.out.println("[TEST] JobManager.resumeJob returned: " + resumed);
            if (!resumed) {
                throw new AssertionError("Expected resumeJob to return true");
            }
            if (job2.getStatus() != JobStatus.QUEUED) {
                throw new AssertionError("Expected job status QUEUED, got: " + job2.getStatus());
            }

            Job.SubTask resumedTask = job2.pollPendingSubTask();
            if (resumedTask == null) {
                throw new AssertionError("Expected pending subtask from resumed job");
            }
            System.out.printf("[TEST] Resumed task polled: %s (Frames: %s, Start: %d, End: %d)\n",
                resumedTask.getTaskId(), resumedTask.getFrameRange(), resumedTask.getStartFrame(), resumedTask.getEndFrame());

            if (resumedTask.getStartFrame() != 31) {
                throw new AssertionError("Expected resumed task to start at frame 31, got: " + resumedTask.getStartFrame());
            }
            if (!"31-50".equals(resumedTask.getFrameRange())) {
                throw new AssertionError("Expected frameRange '31-50', got: " + resumedTask.getFrameRange());
            }

            // Clean up test directories
            for (File f : outDir.listFiles()) f.delete();
            outDir.delete();
            for (File f : outDir2.listFiles()) f.delete();
            outDir2.delete();

            System.out.println("\n==================================================");
            System.out.println("  🎉 RESUME CHECKPOINT TEST PASSED! ALL CHECKS OK 🎉");
            System.out.println("==================================================");
            System.exit(0);

        } catch (Throwable t) {
            System.err.println("[TEST-FAILED] " + t.getMessage());
            t.printStackTrace();
            // Clean up
            if (outDir.exists()) {
                for (File f : outDir.listFiles()) f.delete();
                outDir.delete();
            }
            System.exit(1);
        }
    }
}
