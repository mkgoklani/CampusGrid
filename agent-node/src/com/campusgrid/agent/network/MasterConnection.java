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
        long currentDelay = 2000;
        final long MAX_DELAY = 15000;
        java.util.Random rng = new java.util.Random();

        while (true) {
            try {
                System.out.println("[NETWORK] Connecting to Master at " + masterIp + ":" + masterPort + "...");
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
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
                long jitter = rng.nextInt(1000);
                long waitTime = Math.min(MAX_DELAY, currentDelay + jitter);
                System.out.printf("[NETWORK] Master unavailable (%s). Retrying in %.1fs...\n", e.getMessage(), waitTime / 1000.0);

                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.out.println("[NETWORK] Retry delay interrupted. Retrying connection...");
                }
                currentDelay = Math.min(MAX_DELAY, currentDelay * 2);
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
    public synchronized void sendObject(Object obj) throws IOException {
        if (isConnected() && objectOutputStream != null) {
            objectOutputStream.writeObject(obj);
            objectOutputStream.flush();
            objectOutputStream.reset();
        } else {
            throw new IOException("Cannot send object: not connected to Master.");
        }
    }

    /**
     * Safely disconnects the socket and streams from the Master node.
     * Closes the streams and active socket connection, handling any IOExceptions internally.
     * Also stops the heartbeat service and payload listener.
     */
    public synchronized void disconnect() {
        if (heartbeatService != null) {
            heartbeatService.stop();
        }
        if (payloadListener != null) {
            payloadListener.stop();
        }
        if (objectInputStream != null) {
            try {
                objectInputStream.close();
            } catch (IOException e) {
                // Ignore close error
            }
            objectInputStream = null;
        }
        if (objectOutputStream != null) {
            try {
                objectOutputStream.close();
            } catch (IOException e) {
                // Ignore close error
            }
            objectOutputStream = null;
        }
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // Catch IOException internally to avoid crashing the Agent
            }
        }
        System.out.println("[NETWORK] Disconnected from Master.");
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

    /**
     * Gets the configured Master IP or hostname.
     */
    public String getMasterIp() {
        return masterIp;
    }

    /**
     * Gets the configured Master TCP port.
     */
    public int getMasterPort() {
        return masterPort;
    }
}
