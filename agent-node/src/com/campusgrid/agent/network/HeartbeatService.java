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
    private static final int STARTUP_GRACE_PERIOD_MS = 15000; // 15 seconds startup grace period

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

            // Call LinuxTelemetry to retrieve temperature dynamically before sending heartbeat
            String temp = LinuxTelemetry.getCpuTemperature();

            // Send heartbeat message with CPU temperature to Master node as a String object
            try {
                connection.sendObject("HEARTBEAT | TEMP: " + temp);
            } catch (IOException e) {
                System.out.println("[HEARTBEAT] Connection lost: " + e.getMessage());
                stop();
                break;
            }

            System.out.println("[HEARTBEAT] Sent");

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
