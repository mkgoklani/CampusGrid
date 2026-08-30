package com.campusgrid.core;

import java.io.Serializable;

/**
 * CAMPUS GRID - HEARTBEAT PAYLOAD DTO
 * 
 * Transmitted inside a GridMessage(MessageType.HEARTBEAT) by worker agents
 * to report live hardware temperature, memory consumption, and operating state.
 */
public class HeartbeatPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int cpuTemperature;
    private final double ramUsagePercent;
    private final WorkerStatus status;

    public HeartbeatPayload(int cpuTemperature, double ramUsagePercent, WorkerStatus status) {
        this.cpuTemperature = cpuTemperature;
        this.ramUsagePercent = ramUsagePercent;
        this.status = status;
    }

    public int getCpuTemperature() {
        return cpuTemperature;
    }

    public double getRamUsagePercent() {
        return ramUsagePercent;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return String.format("HeartbeatPayload[Temp=%d°C, RAM=%.2f%%, Status=%s]",
            cpuTemperature, ramUsagePercent, status);
    }
}
