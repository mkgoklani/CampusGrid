package com.campusgrid.core;

import java.io.Serializable;

public class TaskContext implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String jobId;
    private final String workingDirectory; 

    public TaskContext(String jobId, String workingDirectory) {
        this.jobId = jobId;
        this.workingDirectory = workingDirectory;
    }

    public String getJobId() { return jobId; }
    public String getWorkingDirectory() { return workingDirectory; }
}