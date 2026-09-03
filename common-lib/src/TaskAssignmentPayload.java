import java.io.Serializable;

/**
 * CAMPUS GRID - TASK ASSIGNMENT PAYLOAD DTO
 * 
 * Transmitted inside a GridMessage(MessageType.SUBMIT_TASK) from Master to Worker
 * to dispatch a discrete computational slice or rendering chunk.
 */
public class TaskAssignmentPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final String workloadType;
    private final Object taskData;
    private final String assignedFrameRange;
    private final String renderEngine;
    private final int renderSamples;
    private final boolean useDenoising;
    private final int resolutionPercentage;

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange) {
        this(jobId, taskId, workloadType, taskData, assignedFrameRange, "CYCLES", 64, true, 100);
    }

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange, String renderEngine) {
        this(jobId, taskId, workloadType, taskData, assignedFrameRange, renderEngine, 64, true, 100);
    }

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange,
                                String renderEngine, int renderSamples, boolean useDenoising, int resolutionPercentage) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.workloadType = workloadType;
        this.taskData = taskData;
        this.assignedFrameRange = assignedFrameRange;
        this.renderEngine = (renderEngine != null && !renderEngine.trim().isEmpty()) ? renderEngine.trim() : "CYCLES";
        this.renderSamples = renderSamples > 0 ? renderSamples : 64;
        this.useDenoising = useDenoising;
        this.resolutionPercentage = resolutionPercentage > 0 ? resolutionPercentage : 100;
    }

    public String getJobId() { return jobId; }
    public String getTaskId() { return taskId; }
    public String getWorkloadType() { return workloadType; }
    public Object getTaskData() { return taskData; }
    public String getAssignedFrameRange() { return assignedFrameRange; }
    public String getRenderEngine() { return renderEngine != null ? renderEngine : "CYCLES"; }
    public int getRenderSamples() { return renderSamples; }
    public boolean isUseDenoising() { return useDenoising; }
    public int getResolutionPercentage() { return resolutionPercentage; }

    @Override
    public String toString() {
        return String.format("TaskAssignmentPayload[Job=%s, Task=%s, Type=%s, Frames=%s, Engine=%s, Samples=%d, Res=%d%%]",
            jobId, taskId, workloadType, assignedFrameRange, renderEngine, renderSamples, resolutionPercentage);
    }
}
