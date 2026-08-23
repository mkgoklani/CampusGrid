import java.io.Serializable;

/**
 * CAMPUS GRID - TASK RESULT PAYLOAD DTO
 * 
 * Transmitted inside a GridMessage(MessageType.TASK_COMPLETE) from Worker to Master
 * returning the final computational output bytes, image/blend frame data, and execution status.
 */
public class TaskResultPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final boolean success;
    private final byte[] outputData;
    private final String errorMessage;

    /**
     * Constructs a successful task result payload with binary output data.
     */
    public TaskResultPayload(String jobId, String taskId, byte[] outputData) {
        this(jobId, taskId, true, outputData, null);
    }

    /**
     * Constructs a failed task result payload with an error description.
     */
    public TaskResultPayload(String jobId, String taskId, String errorMessage) {
        this(jobId, taskId, false, null, errorMessage);
    }

    /**
     * Full constructor for TaskResultPayload.
     */
    public TaskResultPayload(String jobId, String taskId, boolean success, byte[] outputData, String errorMessage) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.success = success;
        this.outputData = outputData;
        this.errorMessage = errorMessage;
    }

    public String getJobId() {
        return jobId;
    }

    public String getTaskId() {
        return taskId;
    }

    public boolean isSuccess() {
        return success;
    }

    public byte[] getOutputData() {
        return outputData;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return String.format("TaskResultPayload[Job=%s, Task=%s, Success=%b, OutputSize=%d bytes, Err=%s]",
            jobId, taskId, success, outputData != null ? outputData.length : 0, errorMessage);
    }
}
