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

    /**
     * Constructs a new BlenderStatusReport.
     *
     * @param workerId       the unique identifier of the worker agent.
     * @param jobId          the active render job ID.
     * @param currentFrame   the current frame index being processed or completed.
     * @param totalFrames    the total number of frames in the job.
     * @param percentage     the completion percentage (0.0 to 100.0).
     * @param renderFps      the instantaneous or average rendering frames per second.
     * @param cpuTemperature the current CPU temperature reading.
     * @param state          the current state (READY, BUSY, RENDERING, CANCELLED, FAILED, COMPLETED).
     * @param blenderVersion the installed Blender version.
     */
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

    /**
     * Gets the worker ID.
     *
     * @return the worker ID.
     */
    public String getWorkerId() {
        return workerId;
    }

    /**
     * Gets the job ID.
     *
     * @return the job ID.
     */
    public String getJobId() {
        return jobId;
    }

    /**
     * Gets the current frame index.
     *
     * @return the current frame.
     */
    public int getCurrentFrame() {
        return currentFrame;
    }

    /**
     * Gets the total number of frames.
     *
     * @return the total frames.
     */
    public int getTotalFrames() {
        return totalFrames;
    }

    /**
     * Gets the rendering completion percentage.
     *
     * @return the percentage.
     */
    public double getPercentage() {
        return percentage;
    }

    /**
     * Gets the rendering frames per second.
     *
     * @return the render FPS, or -1 if not available.
     */
    public double getRenderFps() {
        return renderFps;
    }

    /**
     * Gets the CPU temperature.
     *
     * @return the CPU temperature.
     */
    public String getCpuTemperature() {
        return cpuTemperature;
    }

    /**
     * Gets the status state.
     *
     * @return the state (READY, BUSY, RENDERING, CANCELLED, FAILED, COMPLETED).
     */
    public String getState() {
        return state;
    }

    /**
     * Gets the Blender version.
     *
     * @return the version.
     */
    public String getBlenderVersion() {
        return blenderVersion;
    }

    @Override
    public String toString() {
        return String.format("BlenderStatusReport[Worker=%s, Job=%s, Frame=%d/%d, Progress=%.1f%%, FPS=%.2f, Temp=%s, State=%s, Blender=%s]",
            workerId, jobId, currentFrame, totalFrames, percentage, renderFps, cpuTemperature, state, blenderVersion);
    }
}
