package com.campusgrid.master;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Lightweight WebSocket Server to broadcast Campus Grid telemetry 
 * directly to the UI dashboard without polling.
 */
public class TelemetryBroadcaster extends WebSocketServer {

    // Thread-safe set to keep track of all connected browsers
    private final Set<WebSocket> activeConnections;

    public TelemetryBroadcaster(int port) {
        super(new InetSocketAddress(port));
        this.activeConnections = Collections.synchronizedSet(new HashSet<>());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        activeConnections.add(conn);
        System.out.println("[WEBSOCKET] New UI connection established: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        activeConnections.remove(conn);
        System.out.println("[WEBSOCKET] UI connection closed.");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // The dashboard mainly listens, but could send commands here (e.g., "CANCEL_JOB")
        System.out.println("[WEBSOCKET] Message from UI: " + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WEBSOCKET] Error occurred: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WEBSOCKET] Telemetry Broadcaster started on port: " + getPort());
    }

    /**
     * Custom method called by Mohit's JobQueue when new data arrives.
     * This blasts the JSON string to all connected dashboard instances.
     */
    public void broadcastUpdate(String jsonPayload) {
        for (WebSocket conn : activeConnections) {
            conn.send(jsonPayload);
        }
    }
}