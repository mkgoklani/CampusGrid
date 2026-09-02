package test;

import java.io.*;
import java.net.*;
import com.campusgrid.agent.blender.BlenderRenderTask;
import com.campusgrid.agent.blender.RenderResult;
import com.campusgrid.agent.network.MasterConnection;

/**
 * Integration test to verify that the Agent renders Blender frames asynchronously
 * and safely aborts/cancels the active process when a Master cancellation command arrives.
 */
public class AsyncRenderCancelTest {

    private static final int PORT = 8080;
    private static volatile boolean testPassed = false;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        System.out.println("=== STARTING ASYNC RENDER & CANCEL INTEGRATION TEST ===");

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
                System.out.println("[SERVER] Mock Master listening on port " + PORT);
                Socket clientSocket = finalServerSocket.accept();
                System.out.println("[SERVER] Connection accepted.");

                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());

                // Read heartbeat
                Object firstMsg = in.readObject();
                System.out.println("[SERVER] Received from agent: " + firstMsg);

                // Dispatch Blender task
                String jobId = "async-job-111";
                String blendFile = new File("test.blend").getAbsolutePath();
                String outputDir = new File("scratch_render_output").getAbsolutePath();
                BlenderRenderTask task = new BlenderRenderTask(jobId, blendFile, 1, 5, outputDir, "BLENDER_EEVEE");
                
                System.out.println("[SERVER] Dispatching BlenderRenderTask...");
                out.writeObject(task);
                out.flush();

                // Read progress updates and wait to send KILL
                for (int i = 0; i < 20; i++) {
                    Object msg = in.readObject();
                    System.out.println("[SERVER] Received: " + msg);
                    if (msg instanceof String && ((String) msg).contains(jobId)) {
                        System.out.println("[SERVER] Progress seen! Sending KILL cancel command...");
                        out.writeObject("KILL");
                        out.flush();
                        break;
                    } else if (msg instanceof com.campusgrid.agent.blender.BlenderStatusReport) {
                        com.campusgrid.agent.blender.BlenderStatusReport rep = (com.campusgrid.agent.blender.BlenderStatusReport) msg;
                        if ("RENDERING".equals(rep.getState())) {
                            System.out.println("[SERVER] Progress seen! Sending KILL cancel command...");
                            out.writeObject("KILL");
                            out.flush();
                            break;
                        }
                    }
                    Thread.sleep(100);
                }

                // Read until RenderResult is received
                for (int k = 0; k < 15; k++) {
                    Object finalMsg = in.readObject();
                    System.out.println("[SERVER] Received message: " + finalMsg);

                    if (finalMsg instanceof RenderResult) {
                        RenderResult res = (RenderResult) finalMsg;
                        if ("CANCELLED".equals(res.getStatus())) {
                            System.out.println("[SERVER] SUCCESS: RenderResult status is CANCELLED.");
                            testPassed = true;
                            break;
                        }
                    }
                }

                clientSocket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        // 3. Start Agent in background
        Thread agentThread = new Thread(() -> {
            try {
                System.out.println("[AGENT] Starting MasterConnection...");
                MasterConnection conn = new MasterConnection("127.0.0.1", PORT);
                conn.connect();
                
                while (running) {
                    Thread.sleep(200);
                }
                
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        agentThread.start();

        // 4. Wait for test execution
        try {
            serverThread.join(30000); // 30s timeout
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

        System.out.println("=== TEST SUMMARY ===");
        if (testPassed) {
            System.out.println("RESULT: PASSED");
            System.exit(0);
        } else {
            System.out.println("RESULT: FAILED");
            System.exit(1);
        }
    }
}
