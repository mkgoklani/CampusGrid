package test;

import java.io.*;
import java.net.*;
import com.campusgrid.agent.blender.BlenderRenderTask;
import com.campusgrid.agent.blender.BlenderStatusReport;
import com.campusgrid.agent.blender.RenderResult;
import com.campusgrid.agent.network.MasterConnection;

/**
 * Test class to execute a real Blender rendering job.
 * This test saves the resulting .blend file and the rendered frames inside the 
 * workspace so they can be inspected visually by the developer.
 */
public class RenderRealBlendTest {

    private static final int PORT = 8080;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("=== REAL BLENDER RENDER TEST ===");

        // 1. Locate and verify Blender
        String blenderPath = com.campusgrid.agent.blender.BlenderUtils.findExecutablePath();
        if (blenderPath == null) {
            System.err.println("[ERROR] Blender was not found on your system PATH or default installation folders.");
            System.exit(1);
        }
        System.out.println("[SETUP] Found Blender at: " + blenderPath);

        // 2. Generate a real Blender file (test.blend) containing a default Cube
        File blendFile = new File("test.blend");
        if (!blendFile.exists()) {
            try {
                System.out.println("[SETUP] Generating default 'test.blend' file containing a Cube scene...");
                ProcessBuilder pb = new ProcessBuilder(
                    blenderPath, "-b", "--python-expr", 
                    "import bpy; bpy.ops.wm.save_as_mainfile(filepath='test.blend')"
                );
                pb.inheritIO();
                pb.start().waitFor();
            } catch (Exception e) {
                System.err.println("[SETUP] Failed to create test.blend: " + e.getMessage());
                System.exit(1);
            }
        }
        System.out.println("[SETUP] Blender scene file ready: " + blendFile.getAbsolutePath());

        // 3. Create destination output folder
        File outputDir = new File("render_output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        System.out.println("[SETUP] Output frames will be written to: " + outputDir.getAbsolutePath());

        // 4. Start Mock Master TCP Server
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            System.err.println("Could not listen on port " + PORT);
            System.exit(1);
        }

        final ServerSocket finalServerSocket = serverSocket;
        Thread serverThread = new Thread(() -> {
            try {
                System.out.println("[SERVER] Master simulation listening on port " + PORT + "...");
                Socket clientSocket = finalServerSocket.accept();
                System.out.println("[SERVER] Connection accepted from Agent.");

                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());

                // Read startup status report
                Object firstMsg = in.readObject();
                System.out.println("[SERVER] Initial status received: " + firstMsg);

                // Dispatch render job for frames 1 to 3
                BlenderRenderTask task = new BlenderRenderTask(
                    "job-real-render-123",
                    blendFile.getAbsolutePath(),
                    1,
                    3,
                    outputDir.getAbsolutePath(),
                    "BLENDER_EEVEE" // Use EEVEE engine for fast hardware-accelerated/real-time viewport style renders
                );
                
                System.out.println("[SERVER] Dispatching BlenderRenderTask to Agent...");
                out.writeObject(task);
                out.flush();

                // Read and print incoming status reports and results
                while (true) {
                    Object msg = in.readObject();
                    if (msg instanceof BlenderStatusReport) {
                        BlenderStatusReport report = (BlenderStatusReport) msg;
                        System.out.printf("[SERVER] Telemetry -> Frame %d/%d, Percentage: %.1f%%, FPS: %.2f, Temp: %s, State: %s\n",
                            report.getCurrentFrame(), report.getTotalFrames(), report.getPercentage(), 
                            report.getRenderFps(), report.getCpuTemperature(), report.getState());
                    } else if (msg instanceof RenderResult) {
                        RenderResult result = (RenderResult) msg;
                        System.out.println("\n[SERVER] RenderResult received from Agent!");
                        System.out.println("----------------------------------------------");
                        System.out.println("Job ID: " + result.getJobId());
                        System.out.println("Worker ID: " + result.getWorkerId());
                        System.out.println("Status: " + result.getStatus());
                        System.out.println("Render Duration: " + result.getRenderDuration() + " ms");
                        System.out.println("Rendered Frames List:");
                        for (String framePath : result.getRenderedFramePaths()) {
                            System.out.println("  -> " + framePath);
                        }
                        System.out.println("----------------------------------------------");
                        break;
                    } else {
                        System.out.println("[SERVER] Received generic packet: " + msg);
                    }
                }

                clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        // 5. Start Agent Thread
        Thread agentThread = new Thread(() -> {
            try {
                System.out.println("[AGENT] Connecting to Master...");
                MasterConnection conn = new MasterConnection("127.0.0.1", PORT);
                conn.connect();
                
                while (running) {
                    Thread.sleep(100);
                }
                
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        agentThread.start();

        // 6. Wait for verification complete
        try {
            serverThread.join(40000); // 40 seconds max duration
            running = false;
            agentThread.join(5000);
            serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\n=== VERIFICATION FINISHED ===");
        System.out.println("You can find the rendered images in: " + outputDir.getAbsolutePath());
        System.out.println("You can view/inspect the Blender scene file at: " + blendFile.getAbsolutePath());
    }
}
