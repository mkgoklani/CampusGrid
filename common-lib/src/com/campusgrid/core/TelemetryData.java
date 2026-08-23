package com.campusgrid.core;

import java.io.Serializable;

/**
 * Streams real-time hardware metrics and availability status to the Master node.
 */
public class TelemetryData implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String workerId;
    private final String status; // e.g., "IDLE", "BUSY"
    private final boolean isStudentActive; // True if xprintidle detects mouse/keyboard use
    private final double cpuUsage;
    private final double ramUsage;
    private final double cpuTemperature;
    private final String activeJobId; // Null if IDLE

    public TelemetryData(String workerId, String status, boolean isStudentActive, double cpuUsage, double ramUsage, double cpuTemperature, String activeJobId) {
        this.workerId = workerId;
        this.status = status;
        this.isStudentActive = isStudentActive;
        this.cpuUsage = cpuUsage;
        this.ramUsage = ramUsage;
        this.cpuTemperature = cpuTemperature;
        this.activeJobId = activeJobId;
    }

    public String getWorkerId() { return workerId; }
    public String getStatus() { return status; }
    public boolean isStudentActive() { return isStudentActive; }
    public double getCpuUsage() { return cpuUsage; }
    public double getRamUsage() { return ramUsage; }
    public double getCpuTemperature() { return cpuTemperature; }
    public String getActiveJobId() { return activeJobId; }
}