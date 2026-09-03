package com.campusgrid.agent.network;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import com.campusgrid.agent.os.LinuxTelemetry;
import com.campusgrid.agent.os.IdleDetector;

/**
 * Service responsible for sending periodic heartbeat signals to the Master node.
 * <p>
 * This service runs in its own background thread and uses the active ObjectOutputStream
 * owned by MasterConnection. It sends the text "HEARTBEAT | TEMP: <cpu_temp>" every 5 seconds.
 * It also checks the IdleDetector before every heartbeat to perform student activity eviction.
 * </p>
 */
public class HeartbeatService implements Runnable {

    private static final int HEARTBEAT_INTERVAL_MS = 5000;
    private static final int EVICTION_SLEEP_MS = 30000; // 30 seconds in milliseconds
    private static final int STARTUP_GRACE_PERIOD_MS = 60000; // 60 seconds (1 minute) startup grace period

    private final MasterConnection connection;
    private volatile boolean running = false;
    private Thread thread;

    /**
     * Constructs a HeartbeatService associated with the given MasterConnection.
     *
     * @param connection the MasterConnection containing the active socket.
     */
    public HeartbeatService(MasterConnection connection) {
        this.connection = connection;
    }

    /**
     * Starts the heartbeat background thread if it is not already running.
     * Prevents duplicate thread creation.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "HeartbeatServiceThread");
        thread.start();
        System.out.println("[HEARTBEAT] Started");
    }

    /**
     * Stops the heartbeat background thread gracefully.
     * Safe to call multiple times.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        System.out.println("[HEARTBEAT] Stopped");
    }

    /**
     * Checks if the heartbeat service is currently running.
     *
     * @return true if the service is running, false otherwise.
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        while (running) {
            if (!connection.isConnected()) {
                System.out.println("[HEARTBEAT] Connection lost");
                stop();
                connection.disconnect();
                break;
            }

            long elapsedTime = System.currentTimeMillis() - startTime;
            boolean inGracePeriod = elapsedTime < STARTUP_GRACE_PERIOD_MS;

            if (inGracePeriod) {
                long remainingSec = (STARTUP_GRACE_PERIOD_MS - elapsedTime) / 1000;
                System.out.println("[HEARTBEAT] Startup grace period active (" + remainingSec + "s remaining)");
            } else if (!LinuxTelemetry.isExecutingTask && IdleDetector.isUserActive()) {
                System.out.println("[HEARTBEAT] User activity detected on idle workstation.");
                
                // Send EVICTED to the Master
                try {
                    connection.sendObject("EVICTED");
                } catch (IOException e) {
                    System.out.println("[HEARTBEAT] Eviction notification failed: " + e.getMessage());
                }

                // Enter sleep mode for 30 seconds
                try {
                    Thread.sleep(EVICTION_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                // After waking, resume heartbeat loop directly
                continue;
            }

            // Call LinuxTelemetry to retrieve authentic hardware metrics dynamically before sending heartbeat
            int tempCelsius = LinuxTelemetry.getCpuTemperatureCelsius();
            double cpuLoad = LinuxTelemetry.getCpuLoadPercent();
            double ramUsage = LinuxTelemetry.getRamUsagePercent();
            String osName = System.getProperty("os.name");
            String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
            boolean isInstalling = com.campusgrid.agent.blender.BlenderInstaller.isInstalling;
            double installPct = com.campusgrid.agent.blender.BlenderInstaller.currentInstallProgress;
            String gpuInfo = com.campusgrid.agent.os.GpuDetector.getGpuInfo();

            String cpuModel = LinuxTelemetry.getCpuModelName();
            String osArch = LinuxTelemetry.getOsArchitecture();
            String agentVer = "v2.0";

            // Send authentic telemetry heartbeat to Master node
            try {
                if (isInstalling && installPct >= 0.0) {
                    connection.sendObject(String.format(java.util.Locale.US,
                        "HEARTBEAT | TEMP: %d°C | CPU: %.1f%% | RAM: %.1f%% | OS: %s | GPU: %s | BLENDER: %s | CPU_MODEL: %s | ARCH: %s | VER: %s | INSTALL: %.1f",
                        tempCelsius, cpuLoad, ramUsage, osName, gpuInfo, blenderVer, cpuModel, osArch, agentVer, installPct));
                } else {
                    connection.sendObject(String.format(java.util.Locale.US,
                        "HEARTBEAT | TEMP: %d°C | CPU: %.1f%% | RAM: %.1f%% | OS: %s | GPU: %s | BLENDER: %s | CPU_MODEL: %s | ARCH: %s | VER: %s",
                        tempCelsius, cpuLoad, ramUsage, osName, gpuInfo, blenderVer, cpuModel, osArch, agentVer));
                }
            } catch (IOException e) {
                System.out.println("[HEARTBEAT] Connection lost: " + e.getMessage());
                stop();
                connection.disconnect();
                break;
            }

            System.out.printf(java.util.Locale.US, "[HEARTBEAT] Sent (Temp: %d°C, CPU: %.1f%%, RAM: %.1f%%, OS: %s, CPU: %s, GPU: %s, Blender: %s%s)\n",
                tempCelsius, cpuLoad, ramUsage, osName, cpuModel, gpuInfo, blenderVer, 
                (isInstalling ? String.format(" [INSTALLING: %.1f%%]", installPct) : ""));

            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                // Restore interrupted status and stop running
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
