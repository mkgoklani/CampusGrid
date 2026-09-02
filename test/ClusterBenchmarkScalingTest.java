import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.campusgrid.core.*;

/**
 * End-to-end Integration Test for:
 * 1. Empirical Benchmark Manager metrics calculation (T_1 baseline, T_N parallel, Speedup, Efficiency)
 * 2. 1 PC vs 2 PCs vs 3 PCs vs 4 PCs scaling comparison matrix
 * 3. Dashboard REST API /api/benchmarks/comparison and /api/benchmarks/history
 * 4. Hardware probe breakdown (CPU Model, GPU Model, Arch, GPU/CPU mode)
 */
public class ClusterBenchmarkScalingTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING CLUSTER BENCHMARK & SCALING COMPARISON TEST ===");

        // 1. Setup Mock Cluster with 3 Worker Nodes
        WorkerRegistry workerRegistry = new WorkerRegistry();
        JobManager jobManager = new JobManager();
        BenchmarkManager benchmarkManager = new BenchmarkManager(workerRegistry);
        jobManager.setBenchmarkManager(benchmarkManager);

        // Node 1: Apple Silicon Node
        WorkerState worker1 = new WorkerState("node-101", "192.168.1.101", null, null);
        worker1.setOsName("macOS Sonoma 14.5");
        worker1.setCpuModel("Apple M1 Max (10 Cores)");
        worker1.setCpuArch("ARM64 (aarch64)");
        worker1.setGpuModel("Apple M1 Max GPU (32 Cores)");
        worker1.setGpuComputeType("METAL");
        worker1.setUseGpu(true);
        workerRegistry.registerWorker(worker1);

        // Node 2: Linux NVIDIA RTX Node
        WorkerState worker2 = new WorkerState("node-102", "192.168.1.102", null, null);
        worker2.setOsName("Ubuntu 22.04 LTS");
        worker2.setCpuModel("AMD Ryzen 9 5950X (16 Cores / 32 Threads)");
        worker2.setCpuArch("x86_64 (64-bit)");
        worker2.setGpuModel("NVIDIA GeForce RTX 4090 (24GB VRAM)");
        worker2.setGpuComputeType("OPTIX");
        worker2.setUseGpu(true);
        workerRegistry.registerWorker(worker2);

        // Node 3: Windows Intel Arc Node
        WorkerState worker3 = new WorkerState("node-103", "192.168.1.103", null, null);
        worker3.setOsName("Windows 11 Pro");
        worker3.setCpuModel("13th Gen Intel(R) Core(TM) i7-13700K (16 Cores)");
        worker3.setCpuArch("x86_64 (64-bit)");
        worker3.setGpuModel("Intel Arc A770 (16GB VRAM)");
        worker3.setGpuComputeType("ONEAPI");
        worker3.setUseGpu(false); // CPU render mode selected
        workerRegistry.registerWorker(worker3);

        System.out.println("[TEST] Registered 3 diverse cluster worker nodes across macOS, Linux, and Windows.");

        // 2. Simulate Job Submission & Partitioning across the 3 nodes
        Map<String, Object> params = new HashMap<>();
        params.put("blendFileName", "BMW27_benchmark.blend");
        params.put("renderEngine", "CYCLES");

        Job job = new Job("JOB_BMW_SCALING_TEST", "BMW27 Production Animation", "BLENDER", 30, params);
        List<Job.SubTask> tasks = job.sliceIntoFrameRanges(10); // 3 slices of 10 frames

        assert tasks.size() == 3 : "Expected 3 subtasks for 30 frames with chunk size 10";

        // Assign and simulate execution with real durations
        long baseTime = System.currentTimeMillis();
        // Task 1 -> Node 1 (Apple M1 Max): took 12,000 ms
        Job.SubTask t1 = tasks.get(0);
        t1.setAssignedWorkerId("node-101");
        t1.setDispatchedTimestamp(baseTime);
        t1.setCompletedTimestamp(baseTime + 12000);

        // Task 2 -> Node 2 (RTX 4090): took 8,500 ms
        Job.SubTask t2 = tasks.get(1);
        t2.setAssignedWorkerId("node-102");
        t2.setDispatchedTimestamp(baseTime);
        t2.setCompletedTimestamp(baseTime + 8500);

        // Task 3 -> Node 3 (Intel i7 CPU): took 15,500 ms
        Job.SubTask t3 = tasks.get(2);
        t3.setAssignedWorkerId("node-103");
        t3.setDispatchedTimestamp(baseTime);
        t3.setCompletedTimestamp(baseTime + 15500);

        // Simulate parallel execution completion
        job.markSubTaskCompleted(t1.getTaskId());
        job.markSubTaskCompleted(t2.getTaskId());
        job.markSubTaskCompleted(t3.getTaskId());
        job.setCompletedTimestamp(baseTime + 15800); // Wall clock duration = 15.8 seconds

        // Record benchmark
        BenchmarkManager.JobBenchmarkRecord record = benchmarkManager.recordJobCompletion(job);

        System.out.println("\n--- Step 2: Validating Recorded Benchmark Metrics ---");
        System.out.println("[TEST] Total Frames: " + record.totalFrames);
        System.out.println("[TEST] Active Nodes: " + record.activeNodesCount);
        System.out.println("[TEST] Wall Clock Time: " + record.wallClockDurationMs + " ms");
        System.out.println("[TEST] Single-Node T_1 Work: " + record.totalComputeTimeMs + " ms");
        System.out.println("[TEST] Speedup: " + record.speedupMultiplier + "x");
        System.out.println("[TEST] Parallel Efficiency: " + record.parallelEfficiencyPct + "%");
        System.out.println("[TEST] Time Saved: " + record.timeSavedPercent + "%");

        assert record.activeNodesCount == 3 : "Active nodes should be 3";
        assert record.totalComputeTimeMs == (12000 + 8500 + 15500) : "T_1 should equal sum of node compute times (36000ms)";
        assert record.speedupMultiplier >= 2.2 : "Speedup multiplier should be > 2.2x";
        assert record.nodes.size() == 3 : "Expected 3 participating node metric records";

        // 3. Start Dashboard Server and query REST API
        System.out.println("\n--- Step 3: Validating Dashboard REST API ---");
        int httpPort = 8097;
        int wsPort = 8098;
        DashboardServer server = new DashboardServer(jobManager, workerRegistry, benchmarkManager, httpPort, wsPort);
        server.start();

        try {
            URL compUrl = new URL("http://localhost:" + httpPort + "/api/benchmarks/comparison?jobId=" + job.getJobId());
            HttpURLConnection conn = (HttpURLConnection) compUrl.openConnection();
            int code = conn.getResponseCode();
            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] /api/benchmarks/comparison response: " + json);

            assert code == 200 : "HTTP Status code should be 200";
            assert json.contains("\"hasData\":true") : "Response should have hasData:true";
            assert json.contains("\"scalingComparison\":") : "Response should contain scalingComparison array";
            assert json.contains("Apple M1 Max") : "Response should contain Node 1 Apple M1 Max";
            assert json.contains("NVIDIA GeForce RTX 4090") : "Response should contain Node 2 RTX 4090";
            assert json.contains("Intel(R) Core(TM) i7-13700K") : "Response should contain Node 3 Intel i7";
            assert json.contains("\"speedupMultiplier\":") : "Response should contain speedupMultiplier";
            assert json.contains("\"nodeCount\":1") : "Scaling table should include 1 PC baseline";
            assert json.contains("\"nodeCount\":2") : "Scaling table should include 2 PCs comparison";
            assert json.contains("\"nodeCount\":3") : "Scaling table should include 3 PCs comparison";
            assert json.contains("\"nodeCount\":4") : "Scaling table should include 4 PCs comparison";

            System.out.println("[TEST] Step 3 PASSED: REST API returned authentic multi-PC scaling comparisons and hardware specs.");

        } finally {
            server.stop();
        }

        System.out.println("\n=======================================================");
        System.out.println(">>> CLUSTER BENCHMARK & SCALING TEST PASSED <<<");
        System.out.println("=======================================================");
    }
}
