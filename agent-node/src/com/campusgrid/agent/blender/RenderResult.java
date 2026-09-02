package com.campusgrid.agent.blender;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the final result of a Blender render job returned to the Master.
 * Includes both output frame file paths and actual binary byte payloads of each frame.
 */
public class RenderResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String workerId;
    private final List<String> renderedFramePaths;
    private final Map<String, byte[]> frameBytesMap;
    private final long renderDuration; // in milliseconds
    private final String status; // e.g. "SUCCESS", "FAILED", "CANCELLED"

    public RenderResult(String jobId, String workerId, List<String> renderedFramePaths, long renderDuration, String status) {
        this(jobId, workerId, renderedFramePaths, null, renderDuration, status);
    }

    public RenderResult(String jobId, String workerId, List<String> renderedFramePaths, Map<String, byte[]> frameBytesMap, long renderDuration, String status) {
        this.jobId = jobId;
        this.workerId = workerId;
        this.renderedFramePaths = renderedFramePaths;
        this.frameBytesMap = frameBytesMap != null ? new HashMap<>(frameBytesMap) : new HashMap<>();
        this.renderDuration = renderDuration;
        this.status = status;
    }

    public String getJobId() {
        return jobId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public List<String> getRenderedFramePaths() {
        return renderedFramePaths;
    }

    public Map<String, byte[]> getFrameBytesMap() {
        return frameBytesMap != null ? Collections.unmodifiableMap(frameBytesMap) : Collections.emptyMap();
    }

    public long getRenderDuration() {
        return renderDuration;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public String toString() {
        int frameCount = (frameBytesMap != null && !frameBytesMap.isEmpty()) 
            ? frameBytesMap.size() 
            : (renderedFramePaths != null ? renderedFramePaths.size() : 0);
        return String.format("RenderResult[jobId=%s, workerId=%s, status=%s, frames=%d, duration=%dms]",
            jobId, workerId, status, frameCount, renderDuration);
    }
}
