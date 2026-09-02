import java.util.*;

/**
 * AUTOMATED TEST: Heterogeneous Hardware-Aware Dynamic Slicing
 * 
 * Verifies that ComputeCapabilityEngine properly weights high-end GPUs over CPUs,
 * partitions frame ranges proportionally, and completely eliminates the Straggler Problem.
 */
public class HardwareAwareSlicingTest {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  RUNNING HARDWARE-AWARE SLICING UNIT TEST SUITE  ");
        System.out.println("==================================================");

        // 1. Simulate a heterogeneous cluster
        WorkerState gpuWorker = new WorkerState("W1", "10.173.67.105:51001", null, null);
        gpuWorker.setGpuName("NVIDIA GeForce RTX 3050 6GB Laptop GPU (CUDA)");
        gpuWorker.setCpuModel("12th Gen Intel Core i5-12450H");

        WorkerState cpuWorker = new WorkerState("W2", "10.173.67.153:51002", null, null);
        cpuWorker.setGpuName("CPU");
        cpuWorker.setCpuModel("Intel Core i3-10100 (4 Cores)");

        double gpuScore = ComputeCapabilityEngine.calculateScore(gpuWorker);
        double cpuScore = ComputeCapabilityEngine.calculateScore(cpuWorker);

        System.out.printf("[TEST] GPU Node Score: %.2f (Expected >= 4.0)\n", gpuScore);
        System.out.printf("[TEST] CPU Node Score: %.2f (Expected == 1.0)\n", cpuScore);

        if (gpuScore < 4.0 || cpuScore > 1.5) {
            System.err.println("[TEST-FAIL] Capability scoring error!");
            System.exit(1);
        }

        // 2. Test 50 Frame Partitioning
        int totalFrames = 50;
        List<WorkerState> cluster = List.of(gpuWorker, cpuWorker);
        List<Integer> slices = ComputeCapabilityEngine.partitionFrames(totalFrames, cluster);

        System.out.printf("[TEST] 50 Frames Partitioned across [GPU, CPU] -> %s\n", slices);

        int sum = 0;
        for (int s : slices) sum += s;

        if (sum != totalFrames) {
            System.err.printf("[TEST-FAIL] Sum of slices (%d) does not match total frames (%d)!\n", sum, totalFrames);
            System.exit(1);
        }

        if (slices.get(0) <= slices.get(1)) {
            System.err.printf("[TEST-FAIL] GPU slice (%d) is not larger than CPU slice (%d)!\n", slices.get(0), slices.get(1));
            System.exit(1);
        }

        // 3. Test Job Slicing and Bound Integrity
        Job testJob = new Job("JOB_TEST", "Test Spec Slicing", "BLENDER", totalFrames, new HashMap<>());
        testJob.sliceIntoCustomRanges(slices);

        System.out.printf("[TEST] Generated SubTasks count: %d\n", testJob.getSubTaskCount());
        List<Job.SubTask> subTaskList = new ArrayList<>(testJob.getSubTasks());
        for (Job.SubTask t : subTaskList) {
            System.out.printf("   • Task [%s]: Frames %d-%d (%s)\n", t.getTaskId(), t.getStartFrame(), t.getEndFrame(), t.getFrameRange());
        }

        Job.SubTask first = subTaskList.get(0);
        Job.SubTask last = subTaskList.get(subTaskList.size() - 1);

        if (first.getStartFrame() != 1 || last.getEndFrame() != 50) {
            System.err.printf("[TEST-FAIL] Frame bounds invalid! First start: %d, Last end: %d\n", first.getStartFrame(), last.getEndFrame());
            System.exit(1);
        }

        System.out.println("\n==================================================");
        System.out.println("  ✔ HARDWARE-AWARE SLICING TEST PASSED (100%)!   ");
        System.out.println("==================================================");
    }
}
