package com.campusgrid.agent.network;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;

/**
 * Service responsible for sending periodic heartbeat signals to the Master node.
 * <p>
 * This service runs in its own background thread and uses the active ObjectOutputStream
 * owned by MasterConnection. It sends a simple text "HEARTBEAT" every 5 seconds.
 * </p>
 */
public class HeartbeatService implements Runnable {

    private static final int HEARTBEAT_INTERVAL_MS = 5000;

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
        PrintWriter writer = null;
        try {
            ObjectOutputStream oos = connection.getObjectOutputStream();
            if (oos == null) {
                System.out.println("[HEARTBEAT] Connection lost");
                stop();
                return;
            }
            writer = new PrintWriter(oos, true);
        } catch (Exception e) {
            System.out.println("[HEARTBEAT] Connection lost");
            stop();
            return;
        }

        while (running) {
            if (!connection.isConnected()) {
                System.out.println("[HEARTBEAT] Connection lost");
                stop();
                break;
            }

            // Send heartbeat message to Master node
            writer.println("HEARTBEAT");
            if (writer.checkError()) {
                System.out.println("[HEARTBEAT] Connection lost");
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
