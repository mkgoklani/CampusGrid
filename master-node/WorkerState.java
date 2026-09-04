import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * CAMPUS GRID - WORKER STATE
 * 
 * Represents the current state and telemetry of a single distributed worker node.
 * This class uses synchronized getter and setter methods to guarantee thread safety,
 * ensuring that updates from connection handlers, heartbeats, and telemetry CLI daemons
 * are visible and consistent across all execution threads.
 */
public class WorkerState {
    
    private final String workerId;
    private final String ipAddress;
    private final Socket socket;
    private final ObjectOutputStream outStream;
    
    // Telemetry and Status fields (Updated concurrently)
    // Telemetry and Status fields (Updated concurrently)
    private volatile WorkerStatus status;
    private volatile int cpuTemperature;
    private volatile double cpuUsagePercent;
    private volatile double ramUsagePercent;
    private volatile String currentJobId;
    private volatile String currentTaskId;
    private volatile String assignedFrameRange;
    private volatile long lastHeartbeatTimestamp;

    // Platform & Blender Environment
    private volatile String osName = "Unknown OS";
    private volatile boolean blenderInstalled = false;
    private volatile String blenderVersion = "Unknown";
    private volatile double installProgress = -1.0; // -1 if not installing, 0-100% when installing

    /**
     * Constructs a new WorkerState.
     * 
     * @param workerId Unique identifier of the worker node.
     * @param ipAddress Net IP address of the worker.
     * @param socket The active network TCP socket.
     * @param outStream The output stream to send serialized payloads.
     */
    public WorkerState(String workerId, String ipAddress, Socket socket, ObjectOutputStream outStream) {
        this.workerId = workerId;
        this.ipAddress = ipAddress;
        this.socket = socket;
        this.outStream = outStream;
        this.status = WorkerStatus.IDLE;
        this.lastHeartbeatTimestamp = System.currentTimeMillis();
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Socket getSocket() {
        return socket;
    }

    public ObjectOutputStream getOutStream() {
        return outStream;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerStatus status) {
        this.status = status;
    }

    public int getCpuTemperature() {
        return cpuTemperature;
    }

    public void setCpuTemperature(int cpuTemperature) {
        this.cpuTemperature = cpuTemperature;
    }

    public double getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    public void setCpuUsagePercent(double cpuUsagePercent) {
        this.cpuUsagePercent = cpuUsagePercent;
    }

    public double getRamUsagePercent() {
        return ramUsagePercent;
    }

    public void setRamUsagePercent(double ramUsagePercent) {
        this.ramUsagePercent = ramUsagePercent;
    }

    public String getCurrentJobId() {
        return currentJobId;
    }

    public void setCurrentJobId(String currentJobId) {
        this.currentJobId = currentJobId;
    }

    public String getCurrentTaskId() {
        return currentTaskId;
    }

    public void setCurrentTaskId(String currentTaskId) {
        this.currentTaskId = currentTaskId;
    }

    public String getAssignedFrameRange() {
        return assignedFrameRange;
    }

    public void setAssignedFrameRange(String assignedFrameRange) {
        this.assignedFrameRange = assignedFrameRange;
    }

    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    public void setLastHeartbeatTimestamp(long lastHeartbeatTimestamp) {
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
    }

    public String getOsName() {
        return osName;
    }

    public void setOsName(String osName) {
        this.osName = osName;
    }

    public boolean isBlenderInstalled() {
        return blenderInstalled;
    }

    public void setBlenderInstalled(boolean blenderInstalled) {
        this.blenderInstalled = blenderInstalled;
    }

    public String getBlenderVersion() {
        return blenderVersion;
    }

    public void setBlenderVersion(String blenderVersion) {
        this.blenderVersion = blenderVersion;
    }

    public double getInstallProgress() {
        return installProgress;
    }

    public void setInstallProgress(double installProgress) {
        this.installProgress = installProgress;
    }

    private volatile String gpuName = "CPU";
    private volatile String cpuModel = "Multi-Core CPU";
    private volatile String osArch = "x86_64 (64-bit)";
    private volatile String agentVersion = "v2.0";

    public String getGpuName() {
        return gpuName != null ? gpuName : "CPU";
    }

    public void setGpuName(String gpuName) {
        this.gpuName = gpuName;
    }

    public String getCpuModel() {
        return cpuModel != null ? cpuModel : "Multi-Core CPU";
    }

    public void setCpuModel(String cpuModel) {
        this.cpuModel = cpuModel;
    }

    public String getOsArch() {
        return osArch != null ? osArch : "x86_64 (64-bit)";
    }

    public void setOsArch(String osArch) {
        this.osArch = osArch;
    }

    public String getAgentVersion() {
        return agentVersion != null ? agentVersion : "v2.0";
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    private volatile String latestFrameUrl = "";
    private volatile int latestFrameNumber = 0;
    private volatile double latestFps = 0.0;

    public String getLatestFrameUrl() {
        return latestFrameUrl != null ? latestFrameUrl : "";
    }

    public void setLatestFrameUrl(String latestFrameUrl) {
        this.latestFrameUrl = latestFrameUrl;
    }

    public int getLatestFrameNumber() {
        return latestFrameNumber;
    }

    public void setLatestFrameNumber(int latestFrameNumber) {
        this.latestFrameNumber = latestFrameNumber;
    }

    public double getLatestFps() {
        return latestFps;
    }

    public void setLatestFps(double latestFps) {
        this.latestFps = latestFps;
    }

    private volatile double reliabilityScore = 1.0;
    private volatile int tasksCompleted = 0;
    private volatile int tasksFailed = 0;

    public double getReliabilityScore() {
        return reliabilityScore;
    }

    public void setReliabilityScore(double score) {
        this.reliabilityScore = Math.max(0.0, Math.min(1.0, score));
    }

    public int getTasksCompleted() {
        return tasksCompleted;
    }

    public void setTasksCompleted(int count) {
        this.tasksCompleted = count;
    }

    public int getTasksFailed() {
        return tasksFailed;
    }

    public void setTasksFailed(int count) {
        this.tasksFailed = count;
    }

    @Override
    public synchronized String toString() {
        return String.format("WorkerState[ID=%s, IP=%s, OS=%s (%s), CPU=%s, GPU=%s, Blender=%s, Status=%s, Temp=%d°C, RAM=%.1f%%, Rel=%.0f%%, Job=%s, Task=%s, Frames=%s]",
            workerId, ipAddress, osName, osArch, cpuModel, gpuName, (blenderInstalled ? blenderVersion : "Not Installed"), status, cpuTemperature, ramUsagePercent, (reliabilityScore * 100.0), currentJobId, currentTaskId, assignedFrameRange);
    }
}
