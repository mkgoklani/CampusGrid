package com.campusgrid.core;

import java.io.Serializable;

/**
 * CAMPUS GRID - TASK PROGRESS PAYLOAD DTO
 * 
 * Transmitted inside a GridMessage(MessageType.TASK_PROGRESS) from Worker to Master
 * to communicate intermediate computation progress and stage messages.
 */
public class TaskProgressPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final double progressPercentage;
    private final String statusMessage;

    public TaskProgressPayload(String jobId, String taskId, double progressPercentage, String statusMessage) {
        this.jobId = jobId != null ? jobId : "UNKNOWN_JOB";
        this.taskId = taskId != null ? taskId : "UNKNOWN_TASK";
        this.progressPercentage = Double.isNaN(progressPercentage) || Double.isInfinite(progressPercentage)
            ? 0.0 : Math.max(0.0, Math.min(100.0, progressPercentage));
        this.statusMessage = statusMessage != null ? statusMessage : "Running";
    }

    public String getJobId() {
        return jobId;
    }

    public String getTaskId() {
        return taskId;
    }

    public double getProgressPercentage() {
        return progressPercentage;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    @Override
    public String toString() {
        return String.format("TaskProgressPayload[Job=%s, Task=%s, Progress=%.1f%%, Msg='%s']",
            jobId, taskId, progressPercentage, statusMessage);
    }
}
