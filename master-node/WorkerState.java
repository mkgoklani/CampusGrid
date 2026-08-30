import java.io.ObjectOutputStream;
import java.net.Socket;
import com.campusgrid.core.*;


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
    private WorkerStatus status;
    private int cpuTemperature;
    private double cpuUsagePercent;
    private double ramUsagePercent;
    private String currentJobId;
    private String currentTaskId;
    private String assignedFrameRange;
    private double currentRenderProgress;
    private int currentRenderFrame;
    private int totalRenderFrames;
    private long lastHeartbeatTimestamp;

    // Platform & Blender Environment
    private String osName = "Unknown OS";
    private boolean blenderInstalled = false;
    private String blenderVersion = "Unknown";
    private double installProgress = -1.0; // -1 if not installing, 0-100% when installing
    private String installMsg = null;
    private boolean taskAssignmentEnabled = true;


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

    public synchronized Socket getSocket() {
        return socket;
    }

    public synchronized ObjectOutputStream getOutStream() {
        return outStream;
    }

    public synchronized WorkerStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(WorkerStatus status) {
        this.status = status;
    }

    public synchronized int getCpuTemperature() {
        return cpuTemperature;
    }

    public synchronized void setCpuTemperature(int cpuTemperature) {
        this.cpuTemperature = cpuTemperature;
    }

    public synchronized double getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    public synchronized void setCpuUsagePercent(double cpuUsagePercent) {
        this.cpuUsagePercent = cpuUsagePercent;
    }

    public synchronized double getRamUsagePercent() {
        return ramUsagePercent;
    }

    public synchronized void setRamUsagePercent(double ramUsagePercent) {
        this.ramUsagePercent = ramUsagePercent;
    }

    public synchronized String getCurrentJobId() {
        return currentJobId;
    }

    public synchronized void setCurrentJobId(String currentJobId) {
        this.currentJobId = currentJobId;
    }

    public synchronized String getCurrentTaskId() {
        return currentTaskId;
    }

    public synchronized void setCurrentTaskId(String currentTaskId) {
        this.currentTaskId = currentTaskId;
    }

    public synchronized String getAssignedFrameRange() {
        return assignedFrameRange;
    }

    public synchronized void setAssignedFrameRange(String assignedFrameRange) {
        this.assignedFrameRange = assignedFrameRange;
    }

    public synchronized double getCurrentRenderProgress() {
        return currentRenderProgress;
    }

    public synchronized void setCurrentRenderProgress(double currentRenderProgress) {
        this.currentRenderProgress = currentRenderProgress;
    }

    public synchronized int getCurrentRenderFrame() {
        return currentRenderFrame;
    }

    public synchronized void setCurrentRenderFrame(int currentRenderFrame) {
        this.currentRenderFrame = currentRenderFrame;
    }

    public synchronized int getTotalRenderFrames() {
        return totalRenderFrames;
    }

    public synchronized void setTotalRenderFrames(int totalRenderFrames) {
        this.totalRenderFrames = totalRenderFrames;
    }

    public synchronized long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    public synchronized void setLastHeartbeatTimestamp(long lastHeartbeatTimestamp) {
        this.lastHeartbeatTimestamp = lastHeartbeatTimestamp;
    }

    public synchronized String getOsName() {
        return osName;
    }

    public synchronized void setOsName(String osName) {
        this.osName = osName;
    }

    public synchronized boolean isBlenderInstalled() {
        return blenderInstalled;
    }

    public synchronized void setBlenderInstalled(boolean blenderInstalled) {
        this.blenderInstalled = blenderInstalled;
    }

    public synchronized String getBlenderVersion() {
        return blenderVersion;
    }

    public synchronized void setBlenderVersion(String blenderVersion) {
        this.blenderVersion = blenderVersion;
    }

    public synchronized double getInstallProgress() {
        return installProgress;
    }

    public synchronized void setInstallProgress(double installProgress) {
        this.installProgress = installProgress;
    }

    public synchronized boolean isTaskAssignmentEnabled() {
        return taskAssignmentEnabled;
    }

    public synchronized void setTaskAssignmentEnabled(boolean taskAssignmentEnabled) {
        this.taskAssignmentEnabled = taskAssignmentEnabled;
    }

    public synchronized String getInstallMsg() {
        return installMsg;
    }

    public synchronized void setInstallMsg(String installMsg) {
        this.installMsg = installMsg;
    }

    @Override
    public synchronized String toString() {
        return String.format("WorkerState[ID=%s, IP=%s, OS=%s, Blender=%s, Status=%s, Temp=%d°C, RAM=%.1f%%, Job=%s, Task=%s, Frames=%s]",
            workerId, ipAddress, osName, (blenderInstalled ? blenderVersion : "Not Installed"), status, cpuTemperature, ramUsagePercent, currentJobId, currentTaskId, assignedFrameRange);
    }
}
