import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.campusgrid.core.*;
import com.campusgrid.agent.os.HardwareCollector;
import com.campusgrid.agent.blender.BlenderJobExecutor;
import com.campusgrid.agent.blender.ProgressReporter;

/**
 * Integration Test for:
 * 1. Multi-platform Hardware Detection (CPU model, CPU Arch, GPU model, GPU compute backend)
 * 2. Heartbeat serialization and parsing
 * 3. Master Dashboard /api/nodes/toggle-gpu endpoint and dynamic state updates
 * 4. Cluster telemetry JSON verification
 * 5. Blender Job Executor GPU/CPU execution backend
 */
public class HardwareDetailsAndGpuToggleTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING HARDWARE DETAILS & GPU TOGGLE INTEGRATION TEST ===");

        // 1. Test Hardware Collector
        System.out.println("\n--- Step 1: Hardware Collector Detection ---");
        String cpuModel = HardwareCollector.getCpuModelName();
        String cpuArch = HardwareCollector.getCpuArchitecture();
        String gpuModel = HardwareCollector.getGpuModelName();
        String gpuCompute = HardwareCollector.getGpuComputeType();
        boolean gpuAvail = HardwareCollector.isGpuAvailable();

        System.out.println("[TEST] CPU Model Detected: " + cpuModel);
        System.out.println("[TEST] CPU Arch Detected: " + cpuArch);
        System.out.println("[TEST] GPU Model Detected: " + gpuModel);
        System.out.println("[TEST] GPU Compute Type: " + gpuCompute);
        System.out.println("[TEST] GPU Available: " + gpuAvail);

        assert cpuModel != null && !cpuModel.trim().isEmpty() : "CPU Model must not be null/empty";
        assert cpuArch != null && !cpuArch.trim().isEmpty() : "CPU Arch must not be null/empty";
        assert gpuModel != null && !gpuModel.trim().isEmpty() : "GPU Model must not be null/empty";
        assert gpuCompute != null && !gpuCompute.trim().isEmpty() : "GPU Compute Type must not be null/empty";
        System.out.println("[TEST] Step 1 PASSED: Hardware detection verified.");

        // 2. Test HeartbeatPayload Serialization & Deserialization
        System.out.println("\n--- Step 2: HeartbeatPayload Serialization ---");
        HeartbeatPayload hb = new HeartbeatPayload(
            45, 62.5, WorkerStatus.IDLE, 15.0, "macOS 14.5",
            cpuModel, cpuArch, gpuModel, gpuCompute, gpuAvail, true
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(hb);
        oos.flush();

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bais);
        HeartbeatPayload deserialized = (HeartbeatPayload) ois.readObject();

        assert deserialized.getCpuTemperature() == 45 : "CPU Temp mismatch";
        assert deserialized.getCpuModel().equals(cpuModel) : "CPU Model mismatch in HeartbeatPayload";
        assert deserialized.getCpuArch().equals(cpuArch) : "CPU Arch mismatch in HeartbeatPayload";
        assert deserialized.getGpuModel().equals(gpuModel) : "GPU Model mismatch in HeartbeatPayload";
        assert deserialized.getGpuComputeType().equals(gpuCompute) : "GPU Compute mismatch in HeartbeatPayload";
        assert deserialized.isUseGpu() == true : "useGpu mismatch in HeartbeatPayload";
        System.out.println("[TEST] Step 2 PASSED: HeartbeatPayload serialization verified.");

        // 3. Test Master Node Dashboard & /api/nodes/toggle-gpu Endpoint
        System.out.println("\n--- Step 3: Master Dashboard Server & GPU Toggle REST Endpoint ---");
        JobManager jobManager = new JobManager();
        WorkerRegistry workerRegistry = new WorkerRegistry();

        String workerId = "test-worker-101";
        WorkerState worker = new WorkerState(workerId, "127.0.0.1", null, null);
        worker.setOsName("macOS Sonoma");
        worker.setCpuModel(cpuModel);
        worker.setCpuArch(cpuArch);
        worker.setGpuModel(gpuModel);
        worker.setGpuComputeType(gpuCompute);
        worker.setGpuAvailable(gpuAvail);
        worker.setUseGpu(true);
        workerRegistry.registerWorker(worker);

        int httpPort = 8095;
        int wsPort = 8096;
        DashboardServer server = new DashboardServer(jobManager, workerRegistry, httpPort, wsPort);
        server.start();

        try {
            // Verify /api/cluster/status contains hardware telemetry fields
            URL statusUrl = new URL("http://localhost:" + httpPort + "/api/cluster/status");
            HttpURLConnection statusConn = (HttpURLConnection) statusUrl.openConnection();
            int statusCode = statusConn.getResponseCode();
            String statusJson = new String(statusConn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] /api/cluster/status response: " + statusJson);

            assert statusCode == 200 : "Failed GET /api/cluster/status";
            assert statusJson.contains("\"cpuModel\":") : "Missing cpuModel in cluster status JSON";
            assert statusJson.contains("\"cpuArch\":") : "Missing cpuArch in cluster status JSON";
            assert statusJson.contains("\"gpuModel\":") : "Missing gpuModel in cluster status JSON";
            assert statusJson.contains("\"gpuComputeType\":") : "Missing gpuComputeType in cluster status JSON";
            assert statusJson.contains("\"useGpu\":true") : "Missing useGpu:true in cluster status JSON";

            // Test Toggle GPU to false
            URL toggleUrl = new URL("http://localhost:" + httpPort + "/api/nodes/toggle-gpu");
            HttpURLConnection toggleConn1 = (HttpURLConnection) toggleUrl.openConnection();
            toggleConn1.setRequestMethod("POST");
            toggleConn1.setDoOutput(true);
            toggleConn1.getOutputStream().write(("{\"workerId\":\"" + workerId + "\",\"enabled\":\"false\"}").getBytes(StandardCharsets.UTF_8));
            
            int toggleCode1 = toggleConn1.getResponseCode();
            String toggleResp1 = new String(toggleConn1.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] POST /api/nodes/toggle-gpu (false) -> " + toggleResp1);
            assert toggleCode1 == 200 : "Toggle GPU (false) returned HTTP " + toggleCode1;
            assert worker.isUseGpu() == false : "Worker state useGpu was not updated to false";

            // Test Toggle GPU back to true
            HttpURLConnection toggleConn2 = (HttpURLConnection) toggleUrl.openConnection();
            toggleConn2.setRequestMethod("POST");
            toggleConn2.setDoOutput(true);
            toggleConn2.getOutputStream().write(("{\"workerId\":\"" + workerId + "\",\"enabled\":\"true\"}").getBytes(StandardCharsets.UTF_8));

            int toggleCode2 = toggleConn2.getResponseCode();
            String toggleResp2 = new String(toggleConn2.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[TEST] POST /api/nodes/toggle-gpu (true) -> " + toggleResp2);
            assert toggleCode2 == 200 : "Toggle GPU (true) returned HTTP " + toggleCode2;
            assert worker.isUseGpu() == true : "Worker state useGpu was not updated to true";

            System.out.println("[TEST] Step 3 PASSED: Dashboard and toggle-gpu REST endpoint verified.");
        } finally {
            server.stop();
        }

        // 4. Test Blender Job Executor validation (Missing file throws FileNotFoundException)
        System.out.println("\n--- Step 4: Blender Job Executor Validation (Strict Mode) ---");
        boolean caughtMissingGpu = false;
        try {
            BlenderJobExecutor.executeJob(
                "JOB_TEST_GPU", "nonexistent_fast.blend", 1, 2, "./render_output/test_gpu", "CYCLES", true, null
            );
        } catch (FileNotFoundException | IllegalStateException e) {
            caughtMissingGpu = true;
            System.out.println("[TEST] Expected exception caught on missing file: " + e.getMessage());
        }
        assert caughtMissingGpu : "Expected FileNotFoundException or IllegalStateException on missing blend file";

        boolean caughtMissingCpu = false;
        try {
            BlenderJobExecutor.executeJob(
                "JOB_TEST_CPU", "nonexistent_fast.blend", 1, 2, "./render_output/test_cpu", "CYCLES", false, null
            );
        } catch (FileNotFoundException | IllegalStateException e) {
            caughtMissingCpu = true;
            System.out.println("[TEST] Expected exception caught on missing file: " + e.getMessage());
        }
        assert caughtMissingCpu : "Expected FileNotFoundException or IllegalStateException on missing blend file";
        System.out.println("[TEST] Step 4 PASSED: BlenderJobExecutor validation verified.");

        System.out.println("\n=======================================================");
        System.out.println(">>> ALL HARDWARE TELEMETRY & GPU TOGGLE TESTS PASSED <<<");
        System.out.println("=======================================================");
    }
}
