package com.campusgrid.agent.blender;

import java.io.Serializable;
import java.util.List;

/**
 * Represents the final result of a Blender render job returned to the Master.
 */
public class RenderResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final String workerId;
    private final List<String> renderedFramePaths;
    private final long renderDuration; // in milliseconds
    private final String status; // e.g. "SUCCESS", "FAILED", "CANCELLED"
    private final byte[] zippedFramesData;

    public RenderResult(String jobId, String taskId, String workerId, List<String> renderedFramePaths, long renderDuration, String status, byte[] zippedFramesData) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.workerId = workerId;
        this.renderedFramePaths = renderedFramePaths;
        this.renderDuration = renderDuration;
        this.status = status;
        this.zippedFramesData = zippedFramesData;
    }

    public RenderResult(String jobId, String workerId, List<String> renderedFramePaths, long renderDuration, String status, byte[] zippedFramesData) {
        this(jobId, null, workerId, renderedFramePaths, renderDuration, status, zippedFramesData);
    }

    public String getJobId() {
        return jobId;
    }

    public String getTaskId() {
        return taskId;
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
     * Gets the rendered frame paths.
     *
     * @return the list of paths.
     */
    public List<String> getRenderedFramePaths() {
        return renderedFramePaths;
    }

    /**
     * Gets the render duration in milliseconds.
     *
     * @return the duration.
     */
    public long getRenderDuration() {
        return renderDuration;
    }

    /**
     * Gets the render status.
     *
     * @return the status.
     */
    public String getStatus() {
        return status;
    }

    public byte[] getZippedFramesData() {
        return zippedFramesData;
    }

    @Override
    public String toString() {
        return String.format("RenderResult[jobId=%s, workerId=%s, status=%s, frames=%d, duration=%dms]",
            jobId, workerId, status, (renderedFramePaths != null ? renderedFramePaths.size() : 0), renderDuration);
    }
}
