import java.io.Serializable;

public class TaskAssignmentPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final String workloadType;
    private final Object taskData;
    private final String assignedFrameRange;

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.workloadType = workloadType;
        this.taskData = taskData;
        this.assignedFrameRange = assignedFrameRange;
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

    @Override
    public String toString() {
        return String.format("TaskAssignmentPayload[Job=%s, Task=%s, Type=%s, Frames=%s]",
            jobId, taskId, workloadType, assignedFrameRange);
    }
}
