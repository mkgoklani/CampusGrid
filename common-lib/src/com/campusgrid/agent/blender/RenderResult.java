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

    public RenderResult(String jobId, String workerId, List<String> renderedFramePaths, long renderDuration, String status) {
        this.jobId = jobId;
        this.workerId = workerId;
        this.renderedFramePaths = renderedFramePaths;
        this.renderDuration = renderDuration;
        this.status = status;
    }

    public String getJobId() { return jobId; }
    public String getWorkerId() { return workerId; }
    public List<String> getRenderedFramePaths() { return renderedFramePaths; }
    public long getRenderDuration() { return renderDuration; }
    public String getStatus() { return status; }

    @Override
    public String toString() {
        return String.format("RenderResult[jobId=%s, workerId=%s, status=%s, frames=%d, duration=%dms]",
            jobId, workerId, status, (renderedFramePaths != null ? renderedFramePaths.size() : 0), renderDuration);
    }
}
