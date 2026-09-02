import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * CAMPUS GRID - RESULT COLLECTOR
 * 
 * Intercepts TASK_COMPLETE result payloads from worker nodes over TCP,
 * persists binary rendered frames and output data to disk under ./output/{jobId}/,
 * and notifies JobManager and WorkerRegistry of task completion.
 */
public class ResultCollector {

    private static final String DEFAULT_OUTPUT_DIR = "./output";

    private final JobManager jobManager;
    private final WorkerRegistry workerRegistry;
    private final Path baseOutputDir;

    /**
     * Constructs a ResultCollector with default output directory (./output).
     */
    public ResultCollector(JobManager jobManager, WorkerRegistry workerRegistry) {
        this(jobManager, workerRegistry, Paths.get(DEFAULT_OUTPUT_DIR));
    }

    /**
     * Constructs a ResultCollector with a custom output path.
     */
    public ResultCollector(JobManager jobManager, WorkerRegistry workerRegistry, Path baseOutputDir) {
        this.jobManager = jobManager;
        this.workerRegistry = workerRegistry;
        this.baseOutputDir = baseOutputDir;
        ensureOutputDirectoryExists();
    }

    private void ensureOutputDirectoryExists() {
        try {
            Files.createDirectories(baseOutputDir);
        } catch (IOException e) {
            System.err.println("[RESULT-COLLECTOR-ERR] Failed to create output directory: " + e.getMessage());
        }
    }

    /**
     * Processes a TaskResultPayload received from a worker.
     * 
     * @param workerId The identifier of the transmitting worker node.
     * @param result The TaskResultPayload containing execution output bytes and status.
     * @return Path to the saved file if successful, null if failed or empty.
     */
    public Path handleTaskResult(String workerId, TaskResultPayload result) {
        if (result == null) return null;

        String jobId = result.getJobId();
        String taskId = result.getTaskId();

        if (result.isSuccess()) {
            Path savedPath = null;
            Path jobDir = baseOutputDir.resolve(jobId);

            try {
                Files.createDirectories(jobDir);
            } catch (IOException e) {
                System.err.println("[RESULT-COLLECTOR-ERR] Failed creating directory " + jobDir + ": " + e.getMessage());
            }

            // 1. Process all received rendered frame PNG binaries
            Map<String, byte[]> frames = result.getRenderedFrames();
            if (frames != null && !frames.isEmpty()) {
                int frameSaveCount = 0;
                for (Map.Entry<String, byte[]> entry : frames.entrySet()) {
                    String frameName = entry.getKey();
                    byte[] frameBytes = entry.getValue();
                    if (frameBytes != null && frameBytes.length > 0) {
                        try {
                            Path framePath = jobDir.resolve(frameName);
                            Files.write(framePath, frameBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            savedPath = framePath;
                            frameSaveCount++;
                        } catch (IOException e) {
                            System.err.printf("[RESULT-COLLECTOR-ERR] Failed saving frame %s for task %s: %s\n",
                                frameName, taskId, e.getMessage());
                        }
                    }
                }
                System.out.printf("[RESULT-COLLECTOR] ✓ Saved %d rendered PNG frame(s) for Task [%s] in %s\n",
                    frameSaveCount, taskId, jobDir.toString());
            }

            // 2. Process generic binary output if present
            byte[] data = result.getOutputData();
            if (data != null && data.length > 0) {
                try {
                    String filename = String.format("%s_output.bin", taskId);
                    Path binPath = jobDir.resolve(filename);
                    Files.write(binPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    if (savedPath == null) savedPath = binPath;

                    System.out.printf("[RESULT-COLLECTOR] ✓ Saved %d bytes for Task [%s] to: %s\n",
                        data.length, taskId, binPath.toString());

                } catch (IOException e) {
                    System.err.printf("[RESULT-COLLECTOR-ERR] Failed to write file for Task [%s]: %s\n",
                        taskId, e.getMessage());
                }
            }

            // 3. Notify JobManager of successful task completion
            jobManager.updateJobProgress(jobId, taskId, true);

            // 4. Free worker state back to IDLE
            freeWorker(workerId);

            return savedPath;

        } else {
            System.err.printf("[RESULT-COLLECTOR-WARN] ⚠ Task [%s] failed on Worker [%s]: %s\n",
                taskId, workerId, result.getErrorMessage());

            // Re-queue task in JobManager for another worker
            jobManager.updateJobProgress(jobId, taskId, false);

            // Free worker back to IDLE
            freeWorker(workerId);

            return null;
        }
    }

    /**
     * Resets a worker node back to IDLE state so it can accept new tasks.
     */
    private void freeWorker(String workerId) {
        for (WorkerState worker : workerRegistry.getAllWorkers()) {
            if (worker.getWorkerId().equals(workerId)) {
                synchronized (worker) {
                    if (worker.getStatus() == WorkerStatus.BUSY) {
                        worker.setStatus(WorkerStatus.IDLE);
                    }
                    worker.setCurrentJobId(null);
                    worker.setCurrentTaskId(null);
                    worker.setAssignedFrameRange(null);
                }
                break;
            }
        }
    }

    public Path getBaseOutputDir() {
        return baseOutputDir;
    }
}
