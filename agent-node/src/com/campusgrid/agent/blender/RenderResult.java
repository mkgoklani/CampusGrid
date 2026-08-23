package com.campusgrid.agent.blender;

import java.io.Serializable;
import java.util.List;

/**
 * Represents the final result of a Blender render job returned to the Master.
 */
public class RenderResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String workerId;
    private final List<String> renderedFramePaths;
    private final long renderDuration; // in milliseconds
    private final String status; // e.g. "SUCCESS", "FAILED", "CANCELLED"

    /**
     * Constructs a new RenderResult.
     *
     * @param jobId              the unique identifier of the render job.
     * @param workerId           the unique identifier of the worker that executed the render.
     * @param renderedFramePaths the list of output frame file paths.
     * @param renderDuration     the execution duration in milliseconds.
     * @param status             the execution status (e.g. "SUCCESS", "FAILED", "CANCELLED").
     */
    public RenderResult(String jobId, String workerId, List<String> renderedFramePaths, long renderDuration, String status) {
        this.jobId = jobId;
        this.workerId = workerId;
        this.renderedFramePaths = renderedFramePaths;
        this.renderDuration = renderDuration;
        this.status = status;
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

    @Override
    public String toString() {
        return String.format("RenderResult[jobId=%s, workerId=%s, status=%s, frames=%d, duration=%dms]",
            jobId, workerId, status, (renderedFramePaths != null ? renderedFramePaths.size() : 0), renderDuration);
    }
}
