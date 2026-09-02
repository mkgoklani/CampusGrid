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
                break;
            }

            long elapsedTime = System.currentTimeMillis() - startTime;
            boolean inGracePeriod = elapsedTime < STARTUP_GRACE_PERIOD_MS;

            if (inGracePeriod) {
                long remainingSec = (STARTUP_GRACE_PERIOD_MS - elapsedTime) / 1000;
                System.out.println("[HEARTBEAT] Startup grace period active (" + remainingSec + "s remaining)");
            } else if (IdleDetector.isUserActive()) {
                System.out.println("[HEARTBEAT] User activity detected.");
                
                // Send EVICTED to the Master
                try {
                    connection.sendObject("EVICTED");
                } catch (IOException e) {
                    System.out.println("[HEARTBEAT] Eviction notification failed: " + e.getMessage());
                }

                // Stop the PayloadListener thread gracefully
                PayloadListener listener = connection.getPayloadListener();
                if (listener != null && listener.isRunning()) {
                    listener.stop();
                }

                // Enter sleep mode for exactly 5 minutes
                try {
                    Thread.sleep(EVICTION_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                // After waking, resume heartbeat loop directly
                continue;
            }

            // Call LinuxTelemetry & HardwareCollector to retrieve authentic hardware metrics dynamically before sending heartbeat
            int tempCelsius = LinuxTelemetry.getCpuTemperatureCelsius();
            double cpuLoad = LinuxTelemetry.getCpuLoadPercent();
            double ramUsage = LinuxTelemetry.getRamUsagePercent();
            String osName = System.getProperty("os.name");
            String cpuModel = com.campusgrid.agent.os.HardwareCollector.getCpuModelName();
            String cpuArch = com.campusgrid.agent.os.HardwareCollector.getCpuArchitecture();
            String gpuModel = com.campusgrid.agent.os.HardwareCollector.getGpuModelName();
            String gpuCompute = com.campusgrid.agent.os.HardwareCollector.getGpuComputeType();
            boolean gpuAvail = com.campusgrid.agent.os.HardwareCollector.isGpuAvailable();
            boolean useGpu = PayloadListener.useGpu;
            String agentVer = com.campusgrid.agent.Agent.CURRENT_VERSION;
            int agentBuild = com.campusgrid.agent.Agent.CURRENT_BUILD;
            String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
            double installProgress = com.campusgrid.agent.blender.BlenderInstaller.currentInstallProgress;
            String progressSuffix = installProgress >= 0 ? String.format(" | PROGRESS: %.1f%%", installProgress) : "";

            // Send authentic telemetry heartbeat to Master node
            try {
                connection.sendObject(String.format(
                    "HEARTBEAT | AGENT_VERSION: %s | AGENT_BUILD: %d | TEMP: %d°C | CPU: %.1f%% | RAM: %.1f%% | OS: %s | CPU_MODEL: %s | ARCH: %s | GPU: %s | GPUTYPE: %s | GPU_AVAIL: %b | USEGPU: %b | BLENDER: %s%s",
                    agentVer, agentBuild, tempCelsius, cpuLoad, ramUsage, osName, cpuModel, cpuArch, gpuModel, gpuCompute, gpuAvail, useGpu, blenderVer, progressSuffix));
            } catch (IOException e) {
                System.out.println("[HEARTBEAT] Connection lost: " + e.getMessage());
                connection.disconnect();
                break;
            }

            System.out.printf("[HEARTBEAT] Sent (Agent: v%s-b%d, Temp: %d°C, CPU: %.1f%%, RAM: %.1f%%, OS: %s, Arch: %s, CPU_Model: %s, GPU: %s [%s, Active: %b], Blender: %s)\n",
                agentVer, agentBuild, tempCelsius, cpuLoad, ramUsage, osName, cpuArch, cpuModel, gpuModel, gpuCompute, useGpu, blenderVer);

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
