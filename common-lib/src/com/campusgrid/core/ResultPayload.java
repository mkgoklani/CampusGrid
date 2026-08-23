package com.campusgrid.core;

import java.io.Serializable;

/**
 * Standardizes how render artifacts and logs are packaged and returned across the network.
 */
public class ResultPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private final byte[] zippedArtifacts; // The compressed PNG frames
    private final String executionLog;
    private final boolean success;

    public ResultPayload(byte[] zippedArtifacts, String executionLog, boolean success) {
        this.zippedArtifacts = zippedArtifacts;
        this.executionLog = executionLog;
        this.success = success;
    }

    public byte[] getZippedArtifacts() { return zippedArtifacts; }
    public String getExecutionLog() { return executionLog; }
    public boolean isSuccess() { return success; }
}