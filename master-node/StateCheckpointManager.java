import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * CAMPUS GRID - STATE CHECKPOINT MANAGER
 * 
 * Provides crash recovery for the Master Node by periodically persisting
 * JobManager state to a JSON checkpoint file (./data/checkpoint.json).
 * 
 * On startup, the master can detect an existing checkpoint and restore
 * interrupted jobs, re-queuing incomplete sub-tasks so rendering resumes
 * from where it left off. Already-rendered frames on disk survive the crash.
 * 
 * Checkpoint cycle: Every 10 seconds (configurable).
 * Format: Lightweight JSON (no external library dependencies).
 */
public class StateCheckpointManager implements Runnable {

    private static final String DEFAULT_CHECKPOINT_DIR = "./data";
    private static final String CHECKPOINT_FILE = "checkpoint.json";
    private static final long DEFAULT_INTERVAL_MS = 10_000; // 10 seconds

    private final JobManager jobManager;
    private final Path checkpointDir;
    private final Path checkpointFile;
    private final long intervalMs;

    private volatile boolean running = false;
    private Thread checkpointThread;

    public StateCheckpointManager(JobManager jobManager) {
        this(jobManager, Paths.get(DEFAULT_CHECKPOINT_DIR), DEFAULT_INTERVAL_MS);
    }

    public StateCheckpointManager(JobManager jobManager, Path checkpointDir, long intervalMs) {
        this.jobManager = jobManager;
        this.checkpointDir = checkpointDir;
        this.checkpointFile = checkpointDir.resolve(CHECKPOINT_FILE);
        this.intervalMs = Math.max(2000, intervalMs);
    }

    /**
     * Starts the periodic checkpoint daemon thread.
     */
    public synchronized void start() {
        if (running) return;
        running = true;

        try {
            Files.createDirectories(checkpointDir);
        } catch (IOException e) {
            System.err.println("[CHECKPOINT-ERR] Failed to create checkpoint directory: " + e.getMessage());
        }

        checkpointThread = new Thread(this, "StateCheckpoint-Daemon");
        checkpointThread.setDaemon(true);
        checkpointThread.start();
        System.out.printf("[CHECKPOINT] State persistence active (Interval: %ds, Path: %s)%n",
            intervalMs / 1000, checkpointFile.toAbsolutePath());
    }

    /**
     * Gracefully stops the checkpoint daemon.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (checkpointThread != null) {
            checkpointThread.interrupt();
            checkpointThread = null;
        }
        // Perform one final checkpoint on shutdown
        saveCheckpoint();
        System.out.println("[CHECKPOINT] State persistence stopped. Final checkpoint saved.");
    }

    @Override
    public void run() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMs);
                saveCheckpoint();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[CHECKPOINT-ERR] Exception during checkpoint: " + e.getMessage());
            }
        }
    }

    /**
     * Persists current JobManager state to the checkpoint JSON file.
     * Uses atomic write (write to temp file, then rename) to prevent corruption.
     */
    public void saveCheckpoint() {
        try {
            String json = serializeJobState();
            Path tempFile = checkpointDir.resolve("checkpoint_tmp.json");
            Files.writeString(tempFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // Atomic rename to prevent partial reads
            Files.move(tempFile, checkpointFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Fallback: non-atomic write if filesystem doesn't support atomic move
            try {
                Path tempFile = checkpointDir.resolve("checkpoint_tmp.json");
                Files.move(tempFile, checkpointFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        } catch (IOException e) {
            System.err.println("[CHECKPOINT-ERR] Failed to save checkpoint: " + e.getMessage());
        }
    }

    /**
     * Attempts to restore job state from the last checkpoint file on startup.
     * Re-queues any incomplete sub-tasks so they can be dispatched to new workers.
     * 
     * @return Number of jobs restored, or 0 if no checkpoint found.
     */
    public int restoreFromCheckpoint() {
        if (!Files.exists(checkpointFile)) {
            System.out.println("[CHECKPOINT] No previous checkpoint found. Starting fresh.");
            return 0;
        }

        try {
            String json = Files.readString(checkpointFile, StandardCharsets.UTF_8);
            int restoredCount = deserializeAndRestoreJobs(json);
            if (restoredCount > 0) {
                System.out.printf("[CHECKPOINT] ★ Crash Recovery: Restored %d job(s) from checkpoint!%n", restoredCount);
                // Rename checkpoint to .recovered to prevent re-loading
                Files.move(checkpointFile, checkpointDir.resolve("checkpoint_recovered.json"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            return restoredCount;
        } catch (Exception e) {
            System.err.println("[CHECKPOINT-ERR] Failed to restore from checkpoint: " + e.getMessage());
            return 0;
        }
    }

    // ========================================================================
    // JSON SERIALIZATION (Zero external dependencies)
    // ========================================================================

    private String serializeJobState() {
        Map<String, Job> allJobs = jobManager.getAllJobs();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"checkpointTimestamp\":").append(System.currentTimeMillis());
        sb.append(",\"version\":\"2.0\"");
        sb.append(",\"jobs\":[");

        int count = 0;
        for (Job job : allJobs.values()) {
            // Only checkpoint active/running/queued jobs (not completed/cancelled)
            JobStatus status = job.getStatus();
            if (status == JobStatus.COMPLETED || status == JobStatus.CANCELLED) {
                continue;
            }

            if (count++ > 0) sb.append(",");
            sb.append("{");
            sb.append("\"jobId\":\"").append(esc(job.getJobId())).append("\",");
            sb.append("\"jobName\":\"").append(esc(job.getJobName())).append("\",");
            sb.append("\"workloadType\":\"").append(esc(job.getWorkloadType())).append("\",");
            sb.append("\"totalFrames\":").append(job.getTotalFrames()).append(",");
            sb.append("\"status\":\"").append(status).append("\",");
            sb.append("\"startTimestamp\":").append(job.getStartTimestamp()).append(",");
            sb.append("\"submissionTimestamp\":").append(job.getSubmissionTimestamp()).append(",");
            sb.append("\"progressPercentage\":").append(String.format(Locale.US, "%.1f", job.getProgressPercentage())).append(",");

            // Serialize parameters (excluding blendFileBytes to avoid huge checkpoints)
            sb.append("\"parameters\":{");
            if (job.getParameters() != null) {
                int pCount = 0;
                for (Map.Entry<String, Object> entry : job.getParameters().entrySet()) {
                    if ("blendFileBytes".equals(entry.getKey())) continue; // Skip binary data
                    if (pCount++ > 0) sb.append(",");
                    sb.append("\"").append(esc(entry.getKey())).append("\":");
                    Object val = entry.getValue();
                    if (val instanceof Number) {
                        sb.append(val);
                    } else if (val instanceof Boolean) {
                        sb.append(val);
                    } else {
                        sb.append("\"").append(esc(String.valueOf(val))).append("\"");
                    }
                }
            }
            sb.append("},");

            // Serialize sub-task completion state
            sb.append("\"subTasks\":[");
            int tCount = 0;
            for (Job.SubTask st : job.getSubTasks()) {
                if (tCount++ > 0) sb.append(",");
                sb.append("{");
                sb.append("\"taskId\":\"").append(esc(st.getTaskId())).append("\",");
                sb.append("\"startFrame\":").append(st.getStartFrame()).append(",");
                sb.append("\"endFrame\":").append(st.getEndFrame()).append(",");
                sb.append("\"frameRange\":\"").append(esc(st.getFrameRange())).append("\",");
                sb.append("\"status\":\"").append(st.getStatus()).append("\",");
                sb.append("\"retryCount\":").append(st.getRetryCount()).append(",");
                sb.append("\"isStolen\":").append(st.isStolen());
                sb.append("}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private int deserializeAndRestoreJobs(String json) {
        int restoredCount = 0;

        // Simple JSON array extraction for jobs
        int jobsStart = json.indexOf("\"jobs\":[");
        if (jobsStart == -1) return 0;

        // Parse individual job blocks
        int searchFrom = jobsStart;
        while (true) {
            int jobStart = json.indexOf("{\"jobId\"", searchFrom);
            if (jobStart == -1) break;

            // Find the matching closing brace (accounting for nested objects/arrays)
            int jobEnd = findMatchingBrace(json, jobStart);
            if (jobEnd == -1) break;

            String jobJson = json.substring(jobStart, jobEnd + 1);
            searchFrom = jobEnd + 1;

            try {
                String jobId = extractStr(jobJson, "jobId");
                String jobName = extractStr(jobJson, "jobName");
                String workloadType = extractStr(jobJson, "workloadType");
                int totalFrames = extractInt(jobJson, "totalFrames", 1);
                String statusStr = extractStr(jobJson, "status");

                // Skip if this job already exists in the manager
                if (jobManager.getJob(jobId) != null) continue;

                // Rebuild parameters
                Map<String, Object> params = new HashMap<>();
                int paramsStart = jobJson.indexOf("\"parameters\":{");
                if (paramsStart != -1) {
                    int pStart = jobJson.indexOf("{", paramsStart + 13);
                    int pEnd = findMatchingBrace(jobJson, pStart);
                    if (pEnd != -1) {
                        String paramsJson = jobJson.substring(pStart + 1, pEnd);
                        parseSimpleParams(paramsJson, params);
                    }
                }

                // Rebuild the Job object
                Job restoredJob = new Job(jobId, jobName, workloadType, totalFrames, params);

                // Determine which frames are already completed (check disk)
                Path outputDir = Paths.get("./output", jobId);
                Set<Integer> completedFrames = new HashSet<>();
                if (Files.exists(outputDir) && Files.isDirectory(outputDir)) {
                    try (var stream = Files.list(outputDir)) {
                        stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                              .forEach(p -> {
                                  String name = p.getFileName().toString();
                                  // Extract frame number from filenames like "frame_0001.png"
                                  try {
                                      String numPart = name.replaceAll("[^0-9]", "");
                                      if (!numPart.isEmpty()) {
                                          completedFrames.add(Integer.parseInt(numPart));
                                      }
                                  } catch (Exception ignored) {}
                              });
                    }
                }

                // Re-slice the job and mark completed frames
                int framesPerTask = 25; // Default slice size
                if (params.containsKey("framesPerTask")) {
                    try {
                        framesPerTask = Integer.parseInt(params.get("framesPerTask").toString());
                    } catch (Exception ignored) {}
                }
                restoredJob.sliceIntoFrameRanges(framesPerTask);

                // Mark sub-tasks whose frames are already rendered as COMPLETED
                int completedCount = 0;
                for (Job.SubTask st : restoredJob.getSubTasks()) {
                    boolean allFramesDone = true;
                    for (int f = st.getStartFrame(); f <= st.getEndFrame(); f++) {
                        if (!completedFrames.contains(f)) {
                            allFramesDone = false;
                            break;
                        }
                    }
                    if (allFramesDone) {
                        restoredJob.markSubTaskCompleted(st.getTaskId());
                        completedCount++;
                    }
                }

                // Determine restored job status
                if (restoredJob.isAllCompleted() || restoredJob.isAllFramesCovered()) {
                    restoredJob.setStatus(JobStatus.COMPLETED);
                    jobManager.registerJob(restoredJob);
                    System.out.printf("[CHECKPOINT] Job [%s] was already complete (%d frames on disk).%n",
                        jobId, completedFrames.size());
                } else if ("CANCELLED".equalsIgnoreCase(statusStr)) {
                    restoredJob.setStatus(JobStatus.CANCELLED);
                    jobManager.registerJob(restoredJob);
                    System.out.printf("[CHECKPOINT] Restored Cancelled Job [%s] \"%s\": %d/%d frames completed (Paused/Cancelled, awaiting manual Resume).%n",
                        jobId, jobName, completedFrames.size(), totalFrames);
                } else {
                    restoredJob.setStatus(JobStatus.QUEUED);
                    jobManager.submitJob(restoredJob, framesPerTask);

                    int pendingFrames = totalFrames - completedFrames.size();
                    System.out.printf("[CHECKPOINT] ★ Restored Job [%s] \"%s\": %d/%d frames completed, %d frames re-queued for rendering.%n",
                        jobId, jobName, completedFrames.size(), totalFrames, pendingFrames);
                    restoredCount++;
                }

            } catch (Exception e) {
                System.err.println("[CHECKPOINT-ERR] Failed to restore job block: " + e.getMessage());
            }
        }

        return restoredCount;
    }

    // ========================================================================
    // MINIMAL JSON PARSING HELPERS (Zero dependencies)
    // ========================================================================

    private static int findMatchingBrace(String json, int openPos) {
        if (openPos < 0 || openPos >= json.length()) return -1;
        char openChar = json.charAt(openPos);
        char closeChar = (openChar == '{') ? '}' : ']';
        int depth = 0;
        boolean inString = false;

        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == openChar) depth++;
                else if (c == closeChar) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static String extractStr(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return "";
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return (end != -1) ? json.substring(start, end) : "";
    }

    private static int extractInt(String json, String key, int defaultVal) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return defaultVal;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static void parseSimpleParams(String paramsJson, Map<String, Object> out) {
        // Lightweight key-value parser for flat JSON objects
        int i = 0;
        while (i < paramsJson.length()) {
            int keyStart = paramsJson.indexOf("\"", i);
            if (keyStart == -1) break;
            int keyEnd = paramsJson.indexOf("\"", keyStart + 1);
            if (keyEnd == -1) break;
            String key = paramsJson.substring(keyStart + 1, keyEnd);

            int colonIdx = paramsJson.indexOf(":", keyEnd);
            if (colonIdx == -1) break;

            int valStart = colonIdx + 1;
            while (valStart < paramsJson.length() && paramsJson.charAt(valStart) == ' ') valStart++;

            if (valStart >= paramsJson.length()) break;

            if (paramsJson.charAt(valStart) == '"') {
                int valEnd = paramsJson.indexOf("\"", valStart + 1);
                if (valEnd == -1) break;
                out.put(key, paramsJson.substring(valStart + 1, valEnd));
                i = valEnd + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < paramsJson.length() && paramsJson.charAt(valEnd) != ',' && paramsJson.charAt(valEnd) != '}') {
                    valEnd++;
                }
                String valStr = paramsJson.substring(valStart, valEnd).trim();
                if ("true".equals(valStr) || "false".equals(valStr)) {
                    out.put(key, Boolean.parseBoolean(valStr));
                } else {
                    try {
                        out.put(key, Integer.parseInt(valStr));
                    } catch (Exception e1) {
                        try {
                            out.put(key, Double.parseDouble(valStr));
                        } catch (Exception e2) {
                            out.put(key, valStr);
                        }
                    }
                }
                i = valEnd;
            }
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
