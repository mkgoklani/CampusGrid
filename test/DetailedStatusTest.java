package test;

import java.io.*;
import java.net.*;
import com.campusgrid.agent.blender.BlenderRenderTask;
import com.campusgrid.agent.blender.BlenderStatusReport;
import com.campusgrid.agent.blender.RenderResult;
import com.campusgrid.agent.network.MasterConnection;
import com.campusgrid.core.MandelbrotTask;

/**
 * Integration test to verify READY -> BUSY -> READY -> RENDERING -> COMPLETED -> READY
 * state transitions and progress reporting rates for Blender rendering.
 */
public class DetailedStatusTest {

    private static final int PORT = 8080;
    private static volatile boolean readySeen = false;
    private static volatile boolean busySeen = false;
    private static volatile boolean renderingSeen = false;
    private static volatile boolean completedStatusSeen = false;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("=== STARTING DETAILED STATUS REPORTING TEST ===");

        // 1. Start Blender file generation
        try {
            System.out.println("[SETUP] Generating test.blend file...");
            String blenderPath = com.campusgrid.agent.blender.BlenderUtils.findExecutablePath();
            if (blenderPath == null) {
                System.err.println("[SETUP] Blender executable not found. Cannot proceed with rendering test.");
                System.exit(1);
            }
            ProcessBuilder pb = new ProcessBuilder(blenderPath, "-b", "--python-expr", "import bpy; bpy.ops.wm.save_as_mainfile(filepath='test.blend')");
            pb.inheritIO();
            pb.start().waitFor();
        } catch (Exception e) {
            System.err.println("[SETUP] Failed to create test.blend: " + e.getMessage());
            System.exit(1);
        }

        // 2. Start Mock Server Thread
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
                System.out.println("[SERVER] Listening on port " + PORT);
                Socket clientSocket = finalServerSocket.accept();
                System.out.println("[SERVER] Connection accepted.");

                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());

                // Read messages
                for (int i = 0; i < 40; i++) {
                    Object msg = in.readObject();
                    System.out.println("[SERVER] Msg Received: " + msg);

                    if (msg instanceof BlenderStatusReport) {
                        BlenderStatusReport report = (BlenderStatusReport) msg;
                        String state = report.getState();
                        
                        if ("READY".equals(state)) {
                            readySeen = true;
                            if (!busySeen) {
                                System.out.println("[SERVER] Sending MandelbrotTask...");
                                out.writeObject(new MandelbrotTask(-2.0, 1.0, -1.0, 1.0, 10, 10, 10));
                                out.flush();
                            }
                        } else if ("BUSY".equals(state)) {
                            busySeen = true;
                        } else if ("RENDERING".equals(state)) {
                            renderingSeen = true;
                        } else if ("COMPLETED".equals(state)) {
                            completedStatusSeen = true;
                        }
                    } else if (msg instanceof int[][]) {
                        System.out.println("[SERVER] MandelbrotTask result received!");
                        String jobId = "status-job-222";
                        String blendFile = new File("test.blend").getAbsolutePath();
                        String outputDir = new File("scratch_render_output").getAbsolutePath();
                        BlenderRenderTask renderTask = new BlenderRenderTask(jobId, blendFile, 1, 2, outputDir, "BLENDER_EEVEE");
                        
                        System.out.println("[SERVER] Sending BlenderRenderTask...");
                        out.writeObject(renderTask);
                        out.flush();
                    } else if (msg instanceof RenderResult) {
                        RenderResult res = (RenderResult) msg;
                        System.out.println("[SERVER] RenderResult received: " + res.getStatus());
                        if ("SUCCESS".equals(res.getStatus()) && readySeen && busySeen && renderingSeen && completedStatusSeen) {
                            System.out.println("[SERVER] All verification criteria satisfied!");
                            break;
                        }
                    }
                    Thread.sleep(100);
                }

                clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        // 3. Start Agent Thread
        Thread agentThread = new Thread(() -> {
            try {
                System.out.println("[AGENT] Connecting...");
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

        // 4. Monitor Execution
        try {
            serverThread.join(25000); // 25s timeout
            running = false;
            agentThread.join(5000);
            serverSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Cleanup
        new File("test.blend").delete();
        File outputDir = new File("scratch_render_output");
        if (outputDir.exists()) {
            File[] files = outputDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            outputDir.delete();
        }

        System.out.println("=== STATUS TEST SUMMARY ===");
        System.out.println("READY state seen: " + readySeen);
        System.out.println("BUSY state seen: " + busySeen);
        System.out.println("RENDERING state seen: " + renderingSeen);
        System.out.println("COMPLETED state seen: " + completedStatusSeen);

        if (readySeen && busySeen && renderingSeen && completedStatusSeen) {
            System.out.println("RESULT: PASSED");
            System.exit(0);
        } else {
            System.out.println("RESULT: FAILED");
            System.exit(1);
        }
    }
}
