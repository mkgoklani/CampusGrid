import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * INTEGRATION TEST: WORKER RELIABILITY, FRAME INTEGRITY & CLUSTER UTILIZATION ANALYTICS
 * 
 * Verifies:
 * 1. FrameIntegrityValidator accurately distinguishes valid PNGs from corrupted/truncated streams.
 * 2. WorkerReliabilityTracker computes dynamic ratings and decays on failures/disconnects.
 * 3. ClusterUtilizationTracker records time-series utilization metrics and serializes analytics.
 */
public class ReliabilityIntegrityAnalyticsTest {

    public static void main(String[] args) {
        System.out.println("=================================================================");
        System.out.println(" TEST: RELIABILITY SCORING, FRAME INTEGRITY & CLUSTER ANALYTICS");
        System.out.println("=================================================================");

        testFrameIntegrityValidator();
        testWorkerReliabilityTracker();
        testClusterUtilizationTracker();

        System.out.println("\n=================================================================");
        System.out.println(" ✔ ALL RELIABILITY, INTEGRITY & ANALYTICS TESTS PASSED (100%)!");
        System.out.println("=================================================================");
    }

    private static void testFrameIntegrityValidator() {
        System.out.println("\n[1/3] Testing FrameIntegrityValidator...");

        // 1. Construct valid synthetic PNG bytes (with signature, IHDR (1920x1080), dummy data, and IEND)
        byte[] validPng = createSyntheticPng(1920, 1080);
        FrameIntegrityValidator.ValidationResult resultValid = FrameIntegrityValidator.validatePng(validPng);

        assertTrue(resultValid.isValid, "Valid PNG must pass integrity check");
        assertTrue(resultValid.width == 1920, "Width should be 1920, got " + resultValid.width);
        assertTrue(resultValid.height == 1080, "Height should be 1080, got " + resultValid.height);
        System.out.printf("  ✓ Valid PNG verified: %dx%d px (%d bytes)%n", resultValid.width, resultValid.height, resultValid.fileSizeBytes);

        // 2. Corrupt PNG Magic Bytes (e.g. text error string instead of PNG)
        byte[] corruptedMagic = "ERROR: Blender execution failed with segfault".getBytes();
        FrameIntegrityValidator.ValidationResult resultBadMagic = FrameIntegrityValidator.validatePng(corruptedMagic);
        assertTrue(!resultBadMagic.isValid, "Corrupted header must fail check");
        assertTrue(resultBadMagic.errorReason.contains("magic byte"), "Reason should mention magic byte");
        System.out.printf("  ✓ Corrupted magic bytes rejected: %s%n", resultBadMagic.errorReason);

        // 3. Truncated PNG (cut off mid-transfer, missing terminal IEND chunk)
        byte[] truncatedPng = new byte[validPng.length - 20];
        System.arraycopy(validPng, 0, truncatedPng, 0, truncatedPng.length);
        FrameIntegrityValidator.ValidationResult resultTruncated = FrameIntegrityValidator.validatePng(truncatedPng);
        assertTrue(!resultTruncated.isValid, "Truncated stream must fail check");
        assertTrue(resultTruncated.errorReason.contains("IEND"), "Reason should mention IEND chunk");
        System.out.printf("  ✓ Truncated PNG rejected: %s%n", resultTruncated.errorReason);

        // 4. Null & Zero-byte payloads
        FrameIntegrityValidator.ValidationResult resultEmpty = FrameIntegrityValidator.validatePng(new byte[0]);
        assertTrue(!resultEmpty.isValid, "Empty payload must fail check");
        System.out.println("  ✓ Zero-byte payload rejected successfully.");
    }

    private static void testWorkerReliabilityTracker() {
        System.out.println("\n[2/3] Testing WorkerReliabilityTracker...");

        WorkerReliabilityTracker tracker = new WorkerReliabilityTracker();
        String nodeA = "192.168.1.101:8080";
        String nodeB = "192.168.1.102:8080";

        // Initial clean-slate score
        assertTrue(tracker.getReliabilityScore(nodeA) == 1.0, "New worker should start with 1.0 reliability");

        // Record 10 successful tasks on Node A
        for (int i = 0; i < 10; i++) {
            tracker.recordTaskSuccess(nodeA, 2500);
        }
        var metricsA = tracker.getMetrics(nodeA);
        assertTrue(metricsA.getTasksCompleted() == 10, "Should have 10 completed tasks");
        assertTrue(metricsA.getTasksFailed() == 0, "Should have 0 failures");
        assertTrue(tracker.getReliabilityScore(nodeA) == 1.0, "Reliability score should remain 1.0 for 100% success");
        System.out.printf("  ✓ Node A (Stable): Score=%s (%d completed, 0 failed, avg %.1fms)%n",
            metricsA.getFormattedScore(), metricsA.getTasksCompleted(), metricsA.getAvgDurationMs());

        // Record 4 successes, 3 failures, and 2 disconnects on Node B
        for (int i = 0; i < 4; i++) tracker.recordTaskSuccess(nodeB, 3000);
        for (int i = 0; i < 3; i++) tracker.recordTaskFailure(nodeB, "Blender timeout");
        tracker.recordWorkerDisconnect(nodeB);
        tracker.recordWorkerDisconnect(nodeB);

        var metricsB = tracker.getMetrics(nodeB);
        double scoreB = tracker.getReliabilityScore(nodeB);
        assertTrue(scoreB < 0.6, "Unstable node reliability should decay below 0.60, got: " + scoreB);
        System.out.printf("  ✓ Node B (Unstable): Score=%s (%d completed, %d failed, %d disconnects)%n",
            metricsB.getFormattedScore(), metricsB.getTasksCompleted(), metricsB.getTasksFailed(), metricsB.getDisconnectCount());

        // Verify JSON serialization
        String jsonA = metricsA.toJson();
        assertTrue(jsonA.contains("\"reliabilityScore\""), "JSON must contain reliabilityScore");
        System.out.println("  ✓ Reliability JSON serialization verified: " + jsonA);
    }

    private static void testClusterUtilizationTracker() {
        System.out.println("\n[3/3] Testing ClusterUtilizationTracker...");

        WorkerRegistry registry = new WorkerRegistry();
        JobManager jobManager = new JobManager();

        // Register 4 workers: 3 BUSY, 1 IDLE (75% utilization)
        WorkerState w1 = new WorkerState("10.0.0.1:8080", "10.0.0.1", null, null);
        w1.setStatus(WorkerStatus.BUSY);
        w1.setCpuTemperature(55);

        WorkerState w2 = new WorkerState("10.0.0.2:8080", "10.0.0.2", null, null);
        w2.setStatus(WorkerStatus.BUSY);
        w2.setCpuTemperature(62);

        WorkerState w3 = new WorkerState("10.0.0.3:8080", "10.0.0.3", null, null);
        w3.setStatus(WorkerStatus.BUSY);
        w3.setCpuTemperature(58);

        WorkerState w4 = new WorkerState("10.0.0.4:8080", "10.0.0.4", null, null);
        w4.setStatus(WorkerStatus.IDLE);
        w4.setCpuTemperature(42);

        registry.registerWorker(w1);
        registry.registerWorker(w2);
        registry.registerWorker(w3);
        registry.registerWorker(w4);

        ClusterUtilizationTracker tracker = new ClusterUtilizationTracker(registry, jobManager, 1000);
        
        // Record 3 samples
        tracker.recordSample();
        tracker.recordSample();
        tracker.recordSample();

        double avgUtil = tracker.getAverageUtilization();
        assertTrue(avgUtil >= 74.0 && avgUtil <= 76.0, "Average utilization should be 75%, got " + avgUtil);
        assertTrue(tracker.getPeakBusyNodes() == 3, "Peak busy nodes should be 3");

        System.out.printf("  ✓ Cluster Utilization recorded: %.1f%% average across %d samples (Peak: %d busy nodes)%n",
            avgUtil, 3, tracker.getPeakBusyNodes());

        String json = tracker.generateAnalyticsJson();
        assertTrue(json.contains("\"avgUtilization\""), "JSON must contain avgUtilization");
        assertTrue(json.contains("\"samples\""), "JSON must contain samples array");
        System.out.println("  ✓ Analytics JSON payload verified: " + json);
    }

    private static byte[] createSyntheticPng(int width, int height) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // 8-byte PNG signature
            baos.write(new byte[] { (byte)0x89, (byte)'P', (byte)'N', (byte)'G', (byte)'\r', (byte)'\n', (byte)0x1A, (byte)'\n' });

            // IHDR Chunk: length (13 bytes), 'IHDR', width, height, bitDepth, colorType, compression, filter, interlace, CRC
            byte[] ihdrData = new byte[13];
            ByteBuffer bb = ByteBuffer.wrap(ihdrData);
            bb.putInt(width);
            bb.putInt(height);
            bb.put((byte) 8);  // 8-bit
            bb.put((byte) 6);  // RGBA
            bb.put((byte) 0);
            bb.put((byte) 0);
            bb.put((byte) 0);

            // Chunk length (13)
            baos.write(ByteBuffer.allocate(4).putInt(13).array());
            // Chunk type
            baos.write(new byte[] { (byte)'I', (byte)'H', (byte)'D', (byte)'R' });
            // Chunk data
            baos.write(ihdrData);
            // Dummy CRC (4 bytes)
            baos.write(new byte[] { 0, 0, 0, 0 });

            // Dummy IDAT Chunk (32 bytes image data)
            baos.write(ByteBuffer.allocate(4).putInt(32).array());
            baos.write(new byte[] { (byte)'I', (byte)'D', (byte)'A', (byte)'T' });
            baos.write(new byte[32]);
            baos.write(new byte[] { 0, 0, 0, 0 });

            // IEND Chunk (0 bytes data)
            baos.write(ByteBuffer.allocate(4).putInt(0).array());
            baos.write(new byte[] { (byte)'I', (byte)'E', (byte)'N', (byte)'D' });
            baos.write(new byte[] { (byte)0xAE, (byte)0x42, (byte)0x60, (byte)0x82 }); // standard IEND CRC

        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            System.err.println("❌ ASSERTION FAILED: " + message);
            throw new AssertionError(message);
        }
    }
}
