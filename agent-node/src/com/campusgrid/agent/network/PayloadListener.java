package com.campusgrid.agent.network;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import com.campusgrid.core.GridTask;

/**
 * Listens for compute tasks (payloads) sent by the Master node over the socket connection.
 * Runs in its own background thread, deserializes incoming objects, executes tasks,
 * and writes the return results back to the Master node.
 * Reuses streams managed by MasterConnection.
 */
public class PayloadListener implements Runnable {

    private final MasterConnection connection;
    private volatile boolean running = false;
    private Thread thread;

    /**
     * Constructs a PayloadListener associated with the given MasterConnection.
     *
     * @param connection the MasterConnection containing the active socket and streams.
     */
    public PayloadListener(MasterConnection connection) {
        this.connection = connection;
    }

    /**
     * Starts the listener background thread if not already running.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "PayloadListenerThread");
        thread.start();
        System.out.println("[TASK] Listener started");
    }

    /**
     * Stops the listener background thread gracefully.
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
        System.out.println("[TASK] Listener stopped");
    }

    /**
     * Checks if the listener is currently running.
     *
     * @return true if the listener thread is active, false otherwise.
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        ObjectOutputStream oos = connection.getObjectOutputStream();
        ObjectInputStream ois = connection.getObjectInputStream();

        if (oos == null || ois == null) {
            System.out.println("[TASK] Connection lost.");
            stop();
            return;
        }

        while (running) {
            if (!connection.isConnected()) {
                System.out.println("[TASK] Connection lost.");
                stop();
                break;
            }

            try {
                System.out.println("[TASK] Waiting for task...");
                Object obj = ois.readObject();
                System.out.println("[TASK] Task received");

                if (obj instanceof GridTask) {
                    GridTask task = (GridTask) obj;
                    System.out.println("[TASK] Executing...");
                    com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = true;
                    Object result = task.execute();
                    com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = false;

                    connection.sendObject(result);
                    System.out.println("[TASK] Result sent");
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("[TASK] Connection lost.");
                stop();
                break;
            }
        }
    }
}
