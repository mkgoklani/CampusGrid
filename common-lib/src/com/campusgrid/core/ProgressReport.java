package com.campusgrid.core;

import java.io.Serializable;

/**
 * Streams incremental execution state (e.g., "Frame 45/75 Complete") to the Master.
 */
public class ProgressReport implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final double percentageComplete;
    private final String statusMessage;

    public ProgressReport(String jobId, String taskId, double percentageComplete, String statusMessage) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.percentageComplete = percentageComplete;
        this.statusMessage = statusMessage;
    }

    public String getJobId() { return jobId; }
    public String getTaskId() { return taskId; }
    public double getPercentageComplete() { return percentageComplete; }
    public String getStatusMessage() { return statusMessage; }
}