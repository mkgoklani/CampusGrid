package com.campusgrid.agent.blender;

import java.io.Serializable;

/**
 * Represents a detailed status and progress report for Blender rendering on a worker agent.
 */
public class BlenderStatusReport implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String workerId;
    private final String jobId;
    private final int currentFrame;
    private final int totalFrames;
    private final double percentage;
    private final double renderFps; // Frames per second (-1 if not available)
    private final String cpuTemperature;
    private final String state; // READY, BUSY, RENDERING, CANCELLED, FAILED, COMPLETED
    private final String blenderVersion;

    public BlenderStatusReport(
            String workerId,
            String jobId,
            int currentFrame,
            int totalFrames,
            double percentage,
            double renderFps,
            String cpuTemperature,
            String state,
            String blenderVersion
    ) {
        this.workerId = workerId;
        this.jobId = jobId;
        this.currentFrame = currentFrame;
        this.totalFrames = totalFrames;
        this.percentage = percentage;
        this.renderFps = renderFps;
        this.cpuTemperature = cpuTemperature;
        this.state = state;
        this.blenderVersion = blenderVersion;
    }

    public String getWorkerId() { return workerId; }
    public String getJobId() { return jobId; }
    public int getCurrentFrame() { return currentFrame; }
    public int getTotalFrames() { return totalFrames; }
    public double getPercentage() { return percentage; }
    public double getRenderFps() { return renderFps; }
    public String getCpuTemperature() { return cpuTemperature; }
    public String getState() { return state; }
    public String getBlenderVersion() { return blenderVersion; }

    @Override
    public String toString() {
        return String.format("BlenderStatusReport[Worker=%s, Job=%s, Frame=%d/%d, Progress=%.1f%%, FPS=%.2f, Temp=%s, State=%s, Blender=%s]",
            workerId, jobId, currentFrame, totalFrames, percentage, renderFps, cpuTemperature, state, blenderVersion);
    }
}
