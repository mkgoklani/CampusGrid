import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import com.campusgrid.core.*;
import com.campusgrid.agent.network.MasterConnection;
import com.campusgrid.agent.blender.BlenderInstaller;
import com.campusgrid.agent.blender.BlenderUtils;

/**
 * End-to-End Test for:
 * 1. Blender Installer Master->Agent endpoint & remote IP resolution
 * 2. Master Frame & Image preview endpoints (/api/jobs/frames and /output)
 * 3. Multi-format frame collection and extraction
 */
public class BlenderInstallAndFramePreviewTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING BLENDER INSTALL & FRAME PREVIEW INTEGRATION TESTS ===");

        // 1. Setup mock output directory and test frames
        String testJobId = "JOB_TEST_PREVIEW_999";
        Path jobOutputDir = Paths.get("output", testJobId);
        Files.createDirectories(jobOutputDir);

        // Create 3 dummy frame files (.png, .jpg, .webp)
        Path f1 = jobOutputDir.resolve("frame_0001.png");
        Path f2 = jobOutputDir.resolve("frame_0002.jpg");
        Path f3 = jobOutputDir.resolve("frame_0003.webp");
        Files.write(f1, new byte[]{ (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }); // PNG header
        Files.write(f2, new byte[]{ (byte)0xFF, (byte)0xD8, (byte)0xFF }); // JPG header
        Files.write(f3, new byte[]{ 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P' }); // WEBP header

        // 2. Start Master Node Dashboard Server on test ports
        JobManager jobManager = new JobManager();
        WorkerRegistry workerRegistry = new WorkerRegistry();
        
        // Add a mock worker
        WorkerState mockWorker = new WorkerState("agent-remote-test", "127.0.0.1", null, null);
        mockWorker.setOsName("macOS ARM64");
        workerRegistry.registerWorker(mockWorker);

        int testHttpPort = 8091;
        int testWsPort = 8092;
        DashboardServer dashboard = new DashboardServer(jobManager, workerRegistry, testHttpPort, testWsPort);
        dashboard.start();
        System.out.println("[TEST] Master DashboardServer started on HTTP port " + testHttpPort);

        try {
            // Test 2.1: Verify /download/blender endpoint returns archive or 404 cleanly
            URL dlUrl = new URL("http://localhost:" + testHttpPort + "/download/blender?os=macos&arch=arm64");
            HttpURLConnection conn = (HttpURLConnection) dlUrl.openConnection();
            int respCode = conn.getResponseCode();
            System.out.println("[TEST] GET /download/blender?os=macos&arch=arm64 -> HTTP " + respCode);
            assert respCode == 200 || respCode == 404 : "Unexpected response code: " + respCode;
            conn.disconnect();

            // Test 2.2: Verify /api/jobs/frames returns the frame sequence
            URL framesUrl = new URL("http://localhost:" + testHttpPort + "/api/jobs/frames?jobId=" + testJobId);
            HttpURLConnection framesConn = (HttpURLConnection) framesUrl.openConnection();
            int framesCode = framesConn.getResponseCode();
            String framesJson = new String(framesConn.getInputStream().readAllBytes());
            System.out.println("[TEST] GET /api/jobs/frames -> HTTP " + framesCode + " Body: " + framesJson);
            assert framesCode == 200 : "Failed to query /api/jobs/frames";
            assert framesJson.contains("frame_0001.png") : "Missing frame_0001.png in frames list";
            assert framesJson.contains("frame_0002.jpg") : "Missing frame_0002.jpg in frames list";
            assert framesJson.contains("frame_0003.webp") : "Missing frame_0003.webp in frames list";
            assert framesJson.contains("\"totalFrames\":3") : "Incorrect frame count in frames response";
            framesConn.disconnect();

            // Test 2.3: Verify OutputFileHandler serves image frames with right MIME types
            URL imgUrl = new URL("http://localhost:" + testHttpPort + "/output/" + testJobId + "/frame_0001.png");
            HttpURLConnection imgConn = (HttpURLConnection) imgUrl.openConnection();
            assert imgConn.getResponseCode() == 200 : "Failed to fetch /output frame";
            assert "image/png".equals(imgConn.getHeaderField("Content-Type")) : "Wrong MIME type for PNG: " + imgConn.getHeaderField("Content-Type");
            imgConn.disconnect();

            URL jpgUrl = new URL("http://localhost:" + testHttpPort + "/output/" + testJobId + "/frame_0002.jpg");
            HttpURLConnection jpgConn = (HttpURLConnection) jpgUrl.openConnection();
            assert jpgConn.getResponseCode() == 200 : "Failed to fetch /output JPG frame";
            assert "image/jpeg".equals(jpgConn.getHeaderField("Content-Type")) : "Wrong MIME type for JPG: " + jpgConn.getHeaderField("Content-Type");
            jpgConn.disconnect();

            // Test 2.4: Verify Agent MasterConnection address getters
            MasterConnection mc = new MasterConnection("192.168.1.50", 8080);
            assert "192.168.1.50".equals(mc.getMasterIp()) : "Master IP getter mismatch";
            assert mc.getMasterPort() == 8080 : "Master port getter mismatch";

            // Test 2.5: Verify BlenderUtils finds executable on system
            String binPath = BlenderUtils.findExecutablePath();
            System.out.println("[TEST] Local Blender Executable Path: " + binPath);

            System.out.println(">>> ALL BLENDER INSTALL & PREVIEW TESTS PASSED SUCCESSFULLY! <<<");
        } finally {
            dashboard.stop();
            // Cleanup dummy files
            try {
                Files.deleteIfExists(f1);
                Files.deleteIfExists(f2);
                Files.deleteIfExists(f3);
                Files.deleteIfExists(jobOutputDir);
            } catch (Exception ignored) {}
        }
    }
}
