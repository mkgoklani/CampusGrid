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

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange) {
        this(jobId, taskId, workloadType, taskData, assignedFrameRange, "CYCLES");
    }

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange, String renderEngine) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.workloadType = workloadType;
        this.taskData = taskData;
        this.assignedFrameRange = assignedFrameRange;
        this.renderEngine = renderEngine;
    }

    public String getJobId() {
        return jobId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getWorkloadType() {
        return workloadType;
    }

    public Object getTaskData() {
        return taskData;
    }

    public String getAssignedFrameRange() {
        return assignedFrameRange;
    }

    public String getRenderEngine() {
        return renderEngine;
    }

    @Override
    public String toString() {
        return String.format("TaskAssignmentPayload[Job=%s, Task=%s, Type=%s, Frames=%s, Engine=%s]",
            jobId, taskId, workloadType, assignedFrameRange, renderEngine);
    }
}
