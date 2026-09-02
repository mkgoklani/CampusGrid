import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * CAMPUS GRID - TASK RESULT PAYLOAD DTO
 * 
 * Transmitted inside a GridMessage(MessageType.TASK_COMPLETE) or directly from Worker to Master
 * to return execution results, error status, and rendered frame binaries.
 */
public class TaskResultPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String taskId;
    private final boolean success;
    private final byte[] outputData;
    private final Map<String, byte[]> renderedFrames;
    private final String errorMessage;
    private final long durationMs;

    public TaskResultPayload(String jobId, String taskId, byte[] outputData) {
        this(jobId, taskId, true, outputData, null, null, 0);
    }

    public TaskResultPayload(String jobId, String taskId, String errorMessage) {
        this(jobId, taskId, false, null, null, errorMessage, 0);
    }

    public TaskResultPayload(String jobId, String taskId, boolean success, byte[] outputData, String errorMessage) {
        this(jobId, taskId, success, outputData, null, errorMessage, 0);
    }

    public TaskResultPayload(String jobId, String taskId, boolean success, byte[] outputData, Map<String, byte[]> renderedFrames, String errorMessage, long durationMs) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.success = success;
        this.outputData = outputData;
        this.renderedFrames = renderedFrames != null ? new HashMap<>(renderedFrames) : new HashMap<>();
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
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

    public Map<String, byte[]> getRenderedFrames() {
        return renderedFrames != null ? Collections.unmodifiableMap(renderedFrames) : Collections.emptyMap();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        int frameCount = renderedFrames != null ? renderedFrames.size() : 0;
        return String.format("TaskResultPayload[Job=%s, Task=%s, Success=%b, Frames=%d, OutputSize=%d bytes, Duration=%dms, Err=%s]",
            jobId, taskId, success, frameCount, outputData != null ? outputData.length : 0, durationMs, errorMessage);
    }
}
