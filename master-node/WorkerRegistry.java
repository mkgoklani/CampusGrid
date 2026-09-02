import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.campusgrid.core.*;


/**
 * CAMPUS GRID - WORKER REGISTRY
 * 
 * A thread-safe, concurrent registry for tracking and managing CampusGrid worker node states.
 * Uses ConcurrentHashMap for lock-free read access to the registry, and synchronizes on 
 * individual WorkerState objects to guarantee atomicity when updating multiple related fields
 * (e.g. task assignments or telemetry sets).
 */
public class WorkerRegistry {

    private final ConcurrentHashMap<String, WorkerState> registry = new ConcurrentHashMap<>();

    /**
     * Registers a new worker node or updates an existing connection.
     * 
     * @param state The WorkerState representing the worker node.
     */
    public void registerWorker(WorkerState state) {
        registry.put(state.getWorkerId(), state);
    }

    /**
     * Removes a worker node from the registry (e.g. during permanent eviction or teardown).
     * 
     * @param workerId Unique identifier of the worker node.
     */
    public void unregisterWorker(String workerId) {
        registry.remove(workerId);
    }

    /**
     * Updates telemetry statistics including CPU load, temperature, and RAM usage.
     * 
     * @param workerId Unique identifier of the worker node.
     * @param cpuTemp The current CPU temperature in °C.
     * @param cpuUsage The current CPU usage percentage.
     * @param ramUsage The current RAM usage percentage.
     */
    public void updateTelemetry(String workerId, int cpuTemp, double cpuUsage, double ramUsage) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            synchronized (state) {
                state.setCpuTemperature(cpuTemp);
                state.setCpuUsagePercent(cpuUsage);
                state.setRamUsagePercent(ramUsage);
                state.setLastHeartbeatTimestamp(System.currentTimeMillis());
            }
        }
    }

    public void updateTelemetry(String workerId, int cpuTemp, double ramUsage) {
        updateTelemetry(workerId, cpuTemp, 0.0, ramUsage);
    }

    /**
     * Updates host OS and Blender runtime environment info.
     */
    public void updateEnvironment(String workerId, String osName, boolean blenderInstalled, String blenderVersion, double installProgress, String installMsg) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            synchronized (state) {
                if (osName != null && !osName.isEmpty()) state.setOsName(osName);
                state.setBlenderInstalled(blenderInstalled);
                if (blenderVersion != null) state.setBlenderVersion(blenderVersion);
                state.setInstallProgress(installProgress);
                if (installMsg != null) state.setInstallMsg(installMsg);
            }
        }
    }

    /**
     * Updates authentic CPU, Architecture, and GPU hardware specifications.
     */
    public void updateHardwareSpecs(String workerId, String cpuModel, String cpuArch, String gpuModel, String gpuComputeType, Boolean gpuAvailable, Boolean useGpu) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            synchronized (state) {
                if (cpuModel != null) state.setCpuModel(cpuModel);
                if (cpuArch != null) state.setCpuArch(cpuArch);
                if (gpuModel != null) state.setGpuModel(gpuModel);
                if (gpuComputeType != null) state.setGpuComputeType(gpuComputeType);
                if (gpuAvailable != null) state.setGpuAvailable(gpuAvailable);
                if (useGpu != null) state.setUseGpu(useGpu);
            }
        }
    }

    /**
     * Safely updates the operational status of a worker node.
     * 
     * @param workerId Unique identifier of the worker node.
     * @param status The new WorkerStatus.
     */
    public void updateStatus(String workerId, WorkerStatus status) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            state.setStatus(status);
        }
    }

    /**
     * Atomically assigns a task/job to a worker and marks their status as BUSY.
     * 
     * @param workerId Unique identifier of the worker node.
     * @param jobId Unique identifier of the task/job.
     * @param taskId Unique identifier of the specific sub-task.
     * @param frameRange The frame range or workload chunk description assigned to this worker.
     */
    public void assignTaskToWorker(String workerId, String jobId, String taskId, String frameRange) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            synchronized (state) {
                state.setStatus(WorkerStatus.BUSY);
                state.setCurrentJobId(jobId);
                state.setCurrentTaskId(taskId);
                state.setAssignedFrameRange(frameRange);
            }
        }
    }

    /**
     * Overloaded assignTaskToWorker for backward compatibility without taskId.
     */
    public void assignTaskToWorker(String workerId, String jobId, String frameRange) {
        assignTaskToWorker(workerId, jobId, null, frameRange);
    }

    /**
     * Returns a collection of all registered workers in the cluster.
     */
    public Collection<WorkerState> getAllWorkers() {
        return registry.values();
    }

    /**
     * Returns a snapshot list of all currently IDLE worker nodes.
     * 
     * @return List of available (IDLE) WorkerState objects.
     */
    public List<WorkerState> getAvailableWorkers() {
        List<WorkerState> available = new ArrayList<>();
        for (WorkerState state : registry.values()) {
            if (state.getStatus() == WorkerStatus.IDLE && state.isTaskAssignmentEnabled()) {
                available.add(state);
            }
        }
        return available;
    }

    /**
     * Safely marks a worker node as OFFLINE.
     * 
     * @param workerId Unique identifier of the worker node.
     */
    public void markWorkerOffline(String workerId) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            synchronized (state) {
                state.setStatus(WorkerStatus.OFFLINE);
                state.setCurrentJobId(null);
                state.setCurrentTaskId(null);
                state.setAssignedFrameRange(null);
            }
        }
    }

    /**
     * Handles a worker crash, heartbeat timeout, or socket failure.
     * Atomically flags the node as OFFLINE, closes dead socket streams,
     * and triggers automatic re-queuing of uncompleted sub-tasks in JobManager.
     *
     * @param workerId Identifier of the dead/timed-out worker node.
     * @param jobManager The active JobManager to receive re-queued work.
     */
    public void handleWorkerFailure(String workerId, JobManager jobManager) {
        WorkerState state = registry.get(workerId);
        if (state != null) {
            String jobId;
            String taskId;
            synchronized (state) {
                state.setStatus(WorkerStatus.OFFLINE);
                jobId = state.getCurrentJobId();
                taskId = state.getCurrentTaskId();
                state.setCurrentJobId(null);
                state.setCurrentTaskId(null);
                state.setAssignedFrameRange(null);

                // Safely close the dead socket
                try {
                    if (state.getSocket() != null && !state.getSocket().isClosed()) {
                        state.getSocket().close();
                    }
                } catch (Exception ignored) {}
            }

            // Automatic Task Re-queuing
            if (jobId != null && jobManager != null) {
                System.out.printf("[FAULT-TOLERANCE] ⚠ Worker [%s] failed. Re-queuing task [%s] for Job [%s]...\n",
                    workerId, taskId != null ? taskId : "UNKNOWN", jobId);
                if (taskId != null) {
                    jobManager.updateJobProgress(jobId, taskId, false);
                } else {
                    Job job = jobManager.getJob(jobId);
                    if (job != null) {
                        for (Job.SubTask st : job.getSubTasks()) {
                            if (workerId.equals(st.getAssignedWorkerId()) && st.getStatus() != Job.SubTaskStatus.COMPLETED) {
                                job.requeueSubTask(st);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Retrieves a snapshot summary of the cluster status.
     * Iterates over registry values in a thread-safe manner.
     * 
     * @return A ClusterSummary object containing node count statistics.
     */
    public ClusterSummary getClusterSummary() {
        int total = registry.size();
        int available = 0;
        int busy = 0;
        int offline = 0;
        int evicted = 0;

        for (WorkerState state : registry.values()) {
            WorkerStatus status = state.getStatus();
            if (status == WorkerStatus.IDLE) {
                available++;
            } else if (status == WorkerStatus.BUSY) {
                busy++;
            } else if (status == WorkerStatus.OFFLINE) {
                offline++;
            } else if (status == WorkerStatus.EVICTED) {
                evicted++;
            }
        }

        return new ClusterSummary(total, available, busy, offline, evicted);
    }

    /**
     * ClusterSummary snapshot record class.
     */
    public static class ClusterSummary {
        public final int total;
        public final int available;
        public final int busy;
        public final int offline;
        public final int evicted;

        public ClusterSummary(int total, int available, int busy, int offline, int evicted) {
            this.total = total;
            this.available = available;
            this.busy = busy;
            this.offline = offline;
            this.evicted = evicted;
        }

        @Override
        public String toString() {
            return String.format("ClusterSummary[Total=%d, Available=%d, Busy=%d, Offline=%d, Evicted=%d]",
                total, available, busy, offline, evicted);
        }
    }
}
