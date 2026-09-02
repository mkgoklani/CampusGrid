package com.campusgrid.core;

import java.io.Serializable;

/**
 * CAMPUS GRID - HEARTBEAT PAYLOAD DTO
 * 
 * Transmitted inside a GridMessage(MessageType.HEARTBEAT) by worker agents
 * to report live hardware temperature, memory consumption, CPU/GPU specifications,
 * and operating state.
 */
public class HeartbeatPayload implements Serializable {

    private static final long serialVersionUID = 3L;

    private final int cpuTemperature;
    private final double ramUsagePercent;
    private final WorkerStatus status;
    private double cpuUsagePercent;
    private String osName;
    private String cpuModel;
    private String cpuArch;
    private String gpuModel;
    private String gpuComputeType;
    private boolean gpuAvailable;
    private boolean useGpu;
    private String agentVersion;
    private int agentBuildNumber;

    public HeartbeatPayload(int cpuTemperature, double ramUsagePercent, WorkerStatus status) {
        this.cpuTemperature = cpuTemperature;
        this.ramUsagePercent = ramUsagePercent;
        this.status = status;
        this.cpuUsagePercent = 0.0;
        this.osName = "Unknown OS";
        this.cpuModel = "Unknown CPU";
        this.cpuArch = "Unknown Arch";
        this.gpuModel = "Unknown GPU";
        this.gpuComputeType = "NONE";
        this.gpuAvailable = false;
        this.useGpu = false;
        this.agentVersion = "1.0.0";
        this.agentBuildNumber = 100;
    }

    public HeartbeatPayload(int cpuTemperature, double ramUsagePercent, WorkerStatus status,
                            double cpuUsagePercent, String osName, String cpuModel, String cpuArch,
                            String gpuModel, String gpuComputeType, boolean gpuAvailable, boolean useGpu) {
        this(cpuTemperature, ramUsagePercent, status, cpuUsagePercent, osName, cpuModel, cpuArch,
             gpuModel, gpuComputeType, gpuAvailable, useGpu, "1.0.0", 100);
    }

    public HeartbeatPayload(int cpuTemperature, double ramUsagePercent, WorkerStatus status,
                            double cpuUsagePercent, String osName, String cpuModel, String cpuArch,
                            String gpuModel, String gpuComputeType, boolean gpuAvailable, boolean useGpu,
                            String agentVersion, int agentBuildNumber) {
        // Defensive bounds sanitization
        this.cpuTemperature = Math.max(0, Math.min(150, cpuTemperature));
        this.ramUsagePercent = Double.isNaN(ramUsagePercent) || Double.isInfinite(ramUsagePercent)
            ? 0.0 : Math.max(0.0, Math.min(100.0, ramUsagePercent));
        this.cpuUsagePercent = Double.isNaN(cpuUsagePercent) || Double.isInfinite(cpuUsagePercent)
            ? 0.0 : Math.max(0.0, Math.min(100.0, cpuUsagePercent));
        this.status = status != null ? status : WorkerStatus.IDLE;
        this.osName = osName != null && !osName.isBlank() ? osName : "Unknown OS";
        this.cpuModel = cpuModel != null && !cpuModel.isBlank() ? cpuModel : "Standard System CPU";
        this.cpuArch = cpuArch != null && !cpuArch.isBlank() ? cpuArch : "Unknown Arch";
        this.gpuModel = gpuModel != null && !gpuModel.isBlank() ? gpuModel : "Integrated Graphics";
        this.gpuComputeType = gpuComputeType != null && !gpuComputeType.isBlank() ? gpuComputeType : "NONE";
        this.gpuAvailable = gpuAvailable;
        this.useGpu = useGpu;
        this.agentVersion = agentVersion != null && !agentVersion.isBlank() ? agentVersion : "1.0.0";
        this.agentBuildNumber = Math.max(1, agentBuildNumber);
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

    public double getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    public String getOsName() {
        return osName;
    }

    public String getCpuModel() {
        return cpuModel;
    }

    public String getCpuArch() {
        return cpuArch;
    }

    public String getGpuModel() {
        return gpuModel;
    }

    public String getGpuComputeType() {
        return gpuComputeType;
    }

    public boolean isGpuAvailable() {
        return gpuAvailable;
    }

    public boolean isUseGpu() {
        return useGpu;
    }

    public String getAgentVersion() {
        return agentVersion != null ? agentVersion : "1.0.0";
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public int getAgentBuildNumber() {
        return agentBuildNumber;
    }

    public void setAgentBuildNumber(int agentBuildNumber) {
        this.agentBuildNumber = agentBuildNumber;
    }

    @Override
    public String toString() {
        return String.format("HeartbeatPayload[Version=v%s(b%d), Temp=%d°C, RAM=%.2f%%, CPU=%.1f%%, CPUModel='%s', Arch='%s', GPU='%s', GpuType='%s', UseGPU=%b, Status=%s]",
            getAgentVersion(), agentBuildNumber, cpuTemperature, ramUsagePercent, cpuUsagePercent, cpuModel, cpuArch, gpuModel, gpuComputeType, useGpu, status);
    }
}
