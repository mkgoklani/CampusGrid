import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * CAMPUS GRID - RESULT COLLECTOR
 * 
 * Intercepts TASK_COMPLETE result payloads from worker nodes over TCP,
 * persists binary rendered frames or output data to disk under ./output/{jobId}/,
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
            byte[] data = result.getOutputData();

            if (data != null && data.length > 0) {
                try {
                    Path jobDir = baseOutputDir.resolve(jobId);
                    Files.createDirectories(jobDir);

                    // Save output binary / frame file
                    String filename = String.format("%s_output.bin", taskId);
                    savedPath = jobDir.resolve(filename);
                    Files.write(savedPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                    System.out.printf("[RESULT-COLLECTOR] ✓ Saved %d bytes for Task [%s] to: %s\n",
                        data.length, taskId, savedPath.toString());

                } catch (IOException e) {
                    System.err.printf("[RESULT-COLLECTOR-ERR] Failed to write file for Task [%s]: %s\n",
                        taskId, e.getMessage());
                }
            }

            // 1. Notify JobManager of successful task completion
            jobManager.updateJobProgress(jobId, taskId, true);

            // 2. Free worker state back to IDLE
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
