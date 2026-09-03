package com.campusgrid.agent.network;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Manages the network connection between the Agent and the Master node.
 * <p>
 * This class is responsible for establishing, maintaining, and retrying the socket 
 * connection to the Master node. It handles all network exception handling and retry delays internally.
 * </p>
 */
public class MasterConnection {

    public static final int MASTER_PORT = 8080;
    private static final long RETRY_DELAY_MS = 5000;

    private final String masterIp;
    private final int masterPort;
    private Socket socket;
    private ObjectInputStream objectInputStream;
    private ObjectOutputStream objectOutputStream;
    private HeartbeatService heartbeatService;
    private PayloadListener payloadListener;
    private final Object writeLock = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean disconnecting = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Constructs a MasterConnection with the specified Master IP address and default port 8080.
     *
     * @param masterIp the IP address of the Master node.
     */
    public MasterConnection(String masterIp) {
        this(masterIp, MASTER_PORT);
    }

    /**
     * Constructs a MasterConnection with the specified Master IP address and port.
     *
     * @param masterIp   the IP address of the Master node.
     * @param masterPort the port of the Master node.
     */
    public MasterConnection(String masterIp, int masterPort) {
        this.masterIp = masterIp;
        this.masterPort = masterPort;
    }

    /**
     * Attempts to connect to the Master node.
     * If the connection fails, prints warning messages, waits for 5 seconds, and 
     * retries indefinitely until a connection is established. All connection exceptions
     * are caught and handled internally.
     * On successful connection, starts the HeartbeatService and PayloadListener.
     */
    public void connect() {
        while (true) {
            try {
                System.out.println("[NETWORK] Connecting to Master...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(masterIp, masterPort), 5000);
                System.out.println("[NETWORK] Connected to Master at " + masterIp + ":" + masterPort);

                // Create streams exactly once for the socket lifetime.
                // Flush ObjectOutputStream first to avoid deadlock.
                objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
                objectOutputStream.flush();
                objectInputStream = new ObjectInputStream(socket.getInputStream());

                // Stop any previous heartbeat service to prepare for a clean session
                if (heartbeatService != null) {
                    heartbeatService.stop();
                }
                // Stop any previous payload listener
                if (payloadListener != null) {
                    payloadListener.stop();
                }

                // Start the heartbeat service automatically
                heartbeatService = new HeartbeatService(this);
                heartbeatService.start();

                // Start the payload listener automatically
                payloadListener = new PayloadListener(this);
                payloadListener.start();
                break;
            } catch (IOException e) {
                System.out.println("[NETWORK] Master unavailable.");
                System.out.println("[NETWORK] Retrying in 5 seconds...");

                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    // Restore interrupted status
                    Thread.currentThread().interrupt();
                    System.out.println("[NETWORK] Retry delay interrupted. Retrying connection...");
                }
            }
        }
    }

    /**
     * Safely and thread-safely sends a serialized object to the Master node.
     * Synchronizes on the ObjectOutputStream to prevent interleaved writes from multiple threads.
     * Also calls reset() to prevent serialization memory leaks.
     *
     * @param obj the object to send
     * @throws IOException if a network error occurs
     */
    public void sendObject(Object obj) throws IOException {
        synchronized (writeLock) {
            if (isConnected() && objectOutputStream != null) {
                objectOutputStream.writeObject(obj);
                objectOutputStream.flush();
                objectOutputStream.reset();
            } else {
                throw new IOException("Cannot send object: not connected to Master.");
            }
        }
    }

    /**
     * Safely disconnects the socket and streams from the Master node.
     * Closes the socket immediately to unblock pending I/O, then stops services
     * without holding monitor locks to prevent deadlocks.
     */
    public void disconnect() {
        if (!disconnecting.compareAndSet(false, true)) {
            return;
        }
        try {
            // 1. Close socket first so any thread blocked on reading or writing unblocks immediately
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
            socket = null;

            // 2. Stop services without holding MasterConnection monitor lock
            if (heartbeatService != null) {
                heartbeatService.stop();
            }
            if (payloadListener != null) {
                payloadListener.stop();
            }

            // 3. Clean up streams
            if (objectInputStream != null) {
                try {
                    objectInputStream.close();
                } catch (IOException ignored) {}
                objectInputStream = null;
            }
            if (objectOutputStream != null) {
                try {
                    objectOutputStream.close();
                } catch (IOException ignored) {}
                objectOutputStream = null;
            }
            System.out.println("[NETWORK] Disconnected from Master.");
        } finally {
            disconnecting.set(false);
        }
    }

    /**
     * Checks if the agent is currently connected to the Master node.
     *
     * @return true if the socket exists, is connected, and is not closed; false otherwise.
     */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Gets the active socket connection to the Master.
     *
     * @return the connected socket instance, or null if not connected.
     */
    public Socket getSocket() {
        return socket;
    }

    /**
     * Gets the active ObjectInputStream instance.
     *
     * @return the active ObjectInputStream, or null if not created.
     */
    public ObjectInputStream getObjectInputStream() {
        return objectInputStream;
    }

    /**
     * Gets the active ObjectOutputStream instance.
     *
     * @return the active ObjectOutputStream, or null if not created.
     */
    public ObjectOutputStream getObjectOutputStream() {
        return objectOutputStream;
    }

    /**
     * Gets the active heartbeat service instance.
     *
     * @return the heartbeat service instance, or null if not created.
     */
    public HeartbeatService getHeartbeatService() {
        return heartbeatService;
    }

    /**
     * Gets the active payload listener instance.
     *
     * @return the payload listener instance, or null if not created.
     */
    public PayloadListener getPayloadListener() {
        return payloadListener;
    }
}
