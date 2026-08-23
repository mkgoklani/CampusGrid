package com.campusgrid.core;

import java.io.Serializable;

/**
 * Wraps a GridTask payload with network-level tracking identifiers.
 */
public class TaskAssignment implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId; // E.g., "job-104-frames-1-75"
    private final GridTask<?> payload;

    public TaskAssignment(String jobId, String taskId, GridTask<?> payload) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.payload = payload;
    }

    public String getJobId() { return jobId; }
    public String getTaskId() { return taskId; }
    public GridTask<?> getPayload() { return payload; }
}