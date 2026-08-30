import java.io.Serializable;
import com.campusgrid.core.RenderSettings;

public class TaskAssignmentPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final String workloadType;
    private final Object taskData;
    private final String assignedFrameRange;
    private final RenderSettings settings;

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange) {
        this(jobId, taskId, workloadType, taskData, assignedFrameRange, null);
    }

    public TaskAssignmentPayload(String jobId, String taskId, String workloadType, Object taskData, String assignedFrameRange, RenderSettings settings) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.workloadType = workloadType;
        this.taskData = taskData;
        this.assignedFrameRange = assignedFrameRange;
        this.settings = settings;
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

    public RenderSettings getSettings() {
        return settings;
    }

    public String getRenderEngine() {
        return settings != null && settings.getRenderEngine() != null ? settings.getRenderEngine().name() : "CYCLES";
    }

    @Override
    public String toString() {
        return String.format("TaskAssignmentPayload[Job=%s, Task=%s, Type=%s, Frames=%s, Settings=%s]",
            jobId, taskId, workloadType, assignedFrameRange, settings);
    }
}
