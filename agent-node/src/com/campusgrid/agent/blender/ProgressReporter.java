package com.campusgrid.agent.blender;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.campusgrid.agent.network.MasterConnection;

/**
 * Sends task/render execution progress updates and status reports back to the Master node.
 * Integrates rate-limiting/throttling to prevent network flooding.
 */
public class ProgressReporter {

    private final MasterConnection connection;
    private final String workerId;
    
    // Track last report time to enforce a ~200ms update limit
    private long lastReportTime = 0;

    /**
     * Constructs a ProgressReporter associated with the given MasterConnection.
     * Automatically resolves the worker's unique ID using its socket endpoint.
     *
     * @param connection the active MasterConnection instance.
     */
    public ProgressReporter(MasterConnection connection) {
        this.connection = connection;
        this.workerId = determineWorkerId(connection);
    }

    /**
     * Constructs a ProgressReporter with a specific worker ID.
     *
     * @param connection the active MasterConnection instance.
     * @param workerId   the explicit identifier for this worker node.
     */
    public ProgressReporter(MasterConnection connection, String workerId) {
        this.connection = connection;
        this.workerId = workerId;
    }

    /**
     * Sends a simple progress message string: "jobId workerId currentFrame totalFrames percentage".
     */
    public void reportProgress(String jobId, int currentFrame, int totalFrames, double percentage) {
        String msg = String.format("%s %s %d %d %.2f", 
            jobId, workerId, currentFrame, totalFrames, percentage);
        
        System.out.println("[PROGRESS] Sending update: " + msg);

        if (connection != null && connection.isConnected()) {
            try {
                connection.sendObject(msg);
            } catch (IOException e) {
                System.err.println("[PROGRESS] Failed to report progress: " + e.getMessage());
            }
        } else {
            System.err.println("[PROGRESS] Cannot report progress: connection is inactive.");
        }
    }

    /**
     * Streams a newly completed rendered PNG frame to the Master node immediately.
     * Enables live frame mosaic viewing and real-time tile updates on the dashboard.
     */
    public void streamFrame(String jobId, int frameNumber, File frameFile) {
        if (connection == null || !connection.isConnected() || frameFile == null || !frameFile.exists()) {
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(frameFile.toPath());
            if (bytes.length > 0) {
                Map<String, byte[]> map = new HashMap<>();
                map.put(frameFile.getName(), bytes);
                RenderResult chunk = new RenderResult(
                    jobId,
                    workerId,
                    Collections.singletonList(frameFile.getAbsolutePath()),
                    map,
                    0,
                    "CHUNK"
                );
                connection.sendObject(chunk);
                System.out.printf("[PROGRESS] ➔ Streamed live frame #%d (%s, %d bytes) to Master\n",
                    frameNumber, frameFile.getName(), bytes.length);
            }
        } catch (Exception e) {
            System.err.println("[PROGRESS] Failed streaming frame " + frameNumber + ": " + e.getMessage());
        }
    }

    /**
     * Sends a complete, detailed Blender status report to the Master node.
     * Enforces rate limiting (~200ms minimum between updates) unless force is true.
     *
     * @param jobId          the active render job ID.
     * @param currentFrame   the current frame index being processed or completed.
     * @param totalFrames    the total number of frames in the job.
     * @param percentage     the completion percentage (0.0 to 100.0).
     * @param renderFps      the rendering frames per second (-1 if not available).
     * @param state          the current state (READY, BUSY, RENDERING, CANCELLED, FAILED, COMPLETED).
     * @param blenderVersion the installed Blender version.
     * @param force          true to bypass rate-limiting (e.g. for final states or initialization).
     */
    public void reportStatus(
            String jobId,
            int currentFrame,
            int totalFrames,
            double percentage,
            double renderFps,
            String state,
            String blenderVersion,
            boolean force
    ) {
        long now = System.currentTimeMillis();
        // Limit progress packets to roughly 200ms minimum interval
        if (!force && (now - lastReportTime < 200)) {
            return;
        }
        lastReportTime = now;

        // Retrieve CPU temperature dynamically
        String cpuTemp = com.campusgrid.agent.os.LinuxTelemetry.getCpuTemperature();

        // Create detailed status report object
        BlenderStatusReport statusReport = new BlenderStatusReport(
            workerId,
            jobId,
            currentFrame,
            totalFrames,
            percentage,
            renderFps,
            cpuTemp,
            state,
            blenderVersion
        );

        System.out.println("[PROGRESS] Reporting Status: " + statusReport);

        if (connection != null && connection.isConnected()) {
            try {
                connection.sendObject(statusReport);
            } catch (IOException e) {
                System.err.println("[PROGRESS] Failed to report status object: " + e.getMessage());
            }
        } else {
            System.err.println("[PROGRESS] Cannot report status: connection is inactive.");
        }
    }

    private static volatile String cachedLocalHost = null;

    private static String getCachedLocalHost() {
        if (cachedLocalHost == null) {
            try {
                cachedLocalHost = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                cachedLocalHost = "127.0.0.1";
            }
        }
        return cachedLocalHost;
    }

    /**
     * Resolves the unique worker ID string dynamically from socket metadata or system hostname.
     *
     * @param conn the active MasterConnection.
     * @return the resolved worker ID.
     */
    private static String determineWorkerId(MasterConnection conn) {
        try {
            if (conn != null && conn.getSocket() != null) {
                String ip = conn.getSocket().getLocalAddress().getHostAddress();
                int port = conn.getSocket().getLocalPort();
                return ip + ":" + port;
            }
            return getCachedLocalHost();
        } catch (Exception e) {
            return "unknown-worker";
        }
    }

    /**
     * Gets the resolved worker ID for this reporter.
     *
     * @return the worker ID string.
     */
    public String getWorkerId() {
        return workerId;
    }
}
