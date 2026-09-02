package com.campusgrid.agent.network;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles automatic updating of the CampusGrid Agent Node.
 * <p>
 * When an outdated agent connects to a Master Node, this updater:
 * 1. Logs detailed version mismatch information.
 * 2. Downloads the latest agent.jar binary directly from the Master HTTP server.
 * 3. Verifies package integrity.
 * 4. Spawns the new updated agent process with the exact same connection arguments.
 * 5. Cleanly terminates the outdated agent process with zero user intervention.
 * </p>
 */
public class AgentUpdater {

    private static volatile boolean updating = false;

    /**
     * Initiates the autonomous auto-update and hot-restart sequence.
     *
     * @param downloadUrl Relative or absolute URL to download the new agent.jar.
     * @param targetVersion The version string of the new agent package.
     * @param targetBuild The build number of the new agent package.
     * @param masterHost The Master node IP or hostname.
     * @param masterPort The Master node TCP port.
     */
    public static synchronized void performAutoUpdate(String downloadUrl, String targetVersion, int targetBuild,
                                                      String masterHost, int masterPort) {
        if (updating) {
            return;
        }
        updating = true;

        new Thread(() -> {
            try {
                System.out.println("\n======================================================================");
                System.out.println("  [AUTO-UPDATE] ⚠ OUTDATED AGENT NODE DETECTED ON CLUSTER");
                System.out.println("  [AUTO-UPDATE] Current Version : " + com.campusgrid.agent.Agent.CURRENT_VERSION + " (Build " + com.campusgrid.agent.Agent.CURRENT_BUILD + ")");
                System.out.println("  [AUTO-UPDATE] Master Version  : " + (targetVersion != null ? targetVersion : "Latest") + " (Build " + targetBuild + ")");
                System.out.println("======================================================================");

                // 1. Resolve full HTTP download URL
                String fullUrl = downloadUrl;
                if (fullUrl == null || fullUrl.trim().isEmpty() || fullUrl.startsWith("/")) {
                    int httpPort = 8081; // Standard Master HTTP port
                    String path = (fullUrl != null && fullUrl.startsWith("/")) ? fullUrl : "/download/agent.jar";
                    fullUrl = "http://" + masterHost + ":" + httpPort + path;
                }

                System.out.println("[AUTO-UPDATE] ⬇ Downloading latest agent binary from: " + fullUrl);

                File updateJarFile = new File("agent.jar");
                File tempJarFile = new File("agent-update.jar");

                // 2. Download into temporary JAR
                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "CampusGrid-Agent-AutoUpdater");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    System.err.println("[AUTO-UPDATE-ERR] ✖ Failed downloading agent package. HTTP Response: " + responseCode);
                    updating = false;
                    return;
                }

                long contentLength = conn.getContentLengthLong();
                long bytesDownloaded = 0;

                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(tempJarFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        bytesDownloaded += bytesRead;
                    }
                }

                System.out.printf("[AUTO-UPDATE] ✓ Successfully downloaded %d bytes of updated agent binary.\n", bytesDownloaded);

                if (bytesDownloaded < 512) {
                    System.err.println("[AUTO-UPDATE-ERR] ✖ Downloaded file is too small to be a valid JAR. Aborting update.");
                    tempJarFile.delete();
                    updating = false;
                    return;
                }

                // 3. Verify JAR package integrity
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(tempJarFile)) {
                    if (jf.getManifest() == null) {
                        System.err.println("[AUTO-UPDATE-ERR] ✖ Downloaded JAR is missing manifest. Aborting update.");
                        tempJarFile.delete();
                        updating = false;
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("[AUTO-UPDATE-ERR] ✖ Downloaded JAR failed integrity validation (" + e.getMessage() + "). Aborting update.");
                    tempJarFile.delete();
                    updating = false;
                    return;
                }

                // 4. Atomically replace target agent.jar or keep temp
                try {
                    Files.copy(tempJarFile.toPath(), updateJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    tempJarFile.delete();
                } catch (Exception e) {
                    // If locked on Windows, use the temp file directly
                    updateJarFile = tempJarFile;
                }

                System.out.println("[AUTO-UPDATE] 💾 Saved new agent package to: " + updateJarFile.getAbsolutePath());
                System.out.println("[AUTO-UPDATE] 🔄 Gracefully disconnecting active connections...");

                // 4. Resolve Java executable path
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    javaBin += ".exe";
                }

                // 5. Construct ProcessBuilder launch arguments
                List<String> command = new ArrayList<>();
                command.add(javaBin);
                command.add("-jar");
                command.add(updateJarFile.getAbsolutePath());
                command.add(masterHost + ":" + masterPort);

                System.out.println("[AUTO-UPDATE] 🚀 Spawning updated Agent process: " + String.join(" ", command));

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.inheritIO();
                pb.start();

                System.out.println("[AUTO-UPDATE] ★ Updated Agent process spawned successfully. Terminating old process.");
                
                // Allow OS buffer flush before exit
                Thread.sleep(500);
                System.exit(0);

            } catch (Exception e) {
                System.err.println("[AUTO-UPDATE-ERR] Exception occurred during auto-update: " + e.getMessage());
                e.printStackTrace();
                updating = false;
            }
        }, "Agent-AutoUpdater-Thread").start();
    }
}
