package com.campusgrid.agent.blender;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a Blender render job in headless mode using ProcessBuilder.
 * Captures process output to track frame completions, update progress with FPS calculations,
 * and support runtime cancellation.
 */
public class BlenderJobExecutor {

    private static final Pattern FRAME_PATTERN = Pattern.compile("Fra:(\\d+)");
    private static final Pattern SAVED_PATTERN = Pattern.compile("Saved:\\s+['\"]?([^'\"]+)['\"]?");
    
    // Tracks active Blender processes keyed by jobId to allow remote cancellation
    private static final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();

    /**
     * Safely terminates a running Blender process for a specific render job.
     *
     * @param jobId the unique identifier of the job to cancel.
     * @return true if a process was found and cancelled, false otherwise.
     */
    public static boolean cancelJob(String jobId) {
        if (jobId == null) {
            return false;
        }
        Process process = activeProcesses.get(jobId);
        if (process != null) {
            System.out.println("[EXECUTOR] Cancelling Blender process for job: " + jobId);
            process.destroyForcibly();
            return true;
        }
        return false;
    }

    /**
     * Executes the given Blender render job.
     * Launches Blender in headless mode and reports progress frame-by-frame.
     *
     * @param jobId         the unique render job identifier.
     * @param blendFilePath the absolute path to the .blend file.
     * @param frameStart    the starting frame of the render range.
     * @param frameEnd      the ending frame of the render range.
     * @param outputDir     the output directory path (can be null/empty).
     * @param renderEngine  the render engine to use (e.g. CYCLES, BLENDER_EEVEE, workbench, etc. - can be null/empty).
     * @param reporter      the ProgressReporter to send updates to Master (can be null).
     * @return the list of absolute file paths for all rendered frames.
     * @throws Exception if execution fails, is cancelled, or the Blender executable is not found.
     */
    public static List<String> executeJob(
            String jobId,
            String blendFilePath,
            int frameStart,
            int frameEnd,
            String outputDir,
            String renderEngine,
            ProgressReporter reporter
    ) throws Exception {

        String blenderPath = BlenderUtils.findExecutablePath();
        if (blenderPath == null) {
            throw new IllegalStateException("Blender executable not found on the host system.");
        }

        File blendFile = new File(blendFilePath);
        if (!blendFile.exists()) {
            throw new IllegalArgumentException("Target .blend file does not exist: " + blendFilePath);
        }

        // Calculate total frames
        int totalFrames = Math.max(1, frameEnd - frameStart + 1);

        // Build command
        List<String> command = new ArrayList<>();
        command.add(blenderPath);
        command.add("-b"); // headless mode
        command.add(blendFilePath);

        if (outputDir != null && !outputDir.trim().isEmpty()) {
            String outPath = outputDir.trim();
            // Blender requires directory outputs to end with a slash, otherwise it treats the last part as a filename prefix
            if (!outPath.endsWith("/") && !outPath.endsWith("\\")) {
                outPath += File.separator;
            }
            command.add("-o");
            command.add(outPath);
            
            // Ensure the directory exists
            File dir = new File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }

        if (renderEngine != null && !renderEngine.trim().isEmpty()) {
            command.add("-E");
            command.add(renderEngine.trim());
        }

        command.add("-s");
        command.add(String.valueOf(frameStart));
        command.add("-e");
        command.add(String.valueOf(frameEnd));
        command.add("-a"); // render animation

        System.out.println("[EXECUTOR] Launching Blender command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        activeProcesses.put(jobId, process);

        List<String> renderedFilePaths = new ArrayList<>();
        int completedFrames = 0;
        int lastSeenFrame = frameStart;

        String blenderVer = BlenderInstaller.getInstallationStatus().getVersion();
        long lastFrameTime = System.currentTimeMillis();

        // Initialize progress at 0%
        if (reporter != null) {
            reporter.reportStatus(jobId, frameStart, totalFrames, 0.0, -1.0, "RENDERING", blenderVer, true);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Check if current thread or process execution was interrupted/cancelled
                if (Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new InterruptedException("Rendering thread was interrupted.");
                }

                // Print stdout to local console for transparency
                System.out.println("[BLENDER] " + line);

                // Detect frame number currently processing
                Matcher frameMatcher = FRAME_PATTERN.matcher(line);
                if (frameMatcher.find()) {
                    try {
                        lastSeenFrame = Integer.parseInt(frameMatcher.group(1));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }

                // Detect completed frame saves
                Matcher savedMatcher = SAVED_PATTERN.matcher(line);
                if (savedMatcher.find()) {
                    String savedPath = savedMatcher.group(1).trim();
                    renderedFilePaths.add(savedPath);
                    completedFrames++;

                    // Calculate instantaneous Render FPS
                    long currentTime = System.currentTimeMillis();
                    double frameDurationSeconds = (currentTime - lastFrameTime) / 1000.0;
                    double renderFps = frameDurationSeconds > 0 ? (1.0 / frameDurationSeconds) : -1.0;
                    lastFrameTime = currentTime;

                    double percentage = ((double) completedFrames / totalFrames) * 100.0;
                    if (reporter != null) {
                        reporter.reportStatus(jobId, lastSeenFrame, totalFrames, percentage, renderFps, "RENDERING", blenderVer, false);
                    }
                }
            }
        } finally {
            activeProcesses.remove(jobId);
        }

        int exitCode = process.waitFor();
        System.out.println("[EXECUTOR] Blender process exited with code: " + exitCode);
        
        if (exitCode != 0) {
            throw new RuntimeException("Blender process failed with exit code: " + exitCode);
        }

        // Report final completion if not fully updated
        if (completedFrames < totalFrames && reporter != null) {
            long currentTime = System.currentTimeMillis();
            double frameDurationSeconds = (currentTime - lastFrameTime) / 1000.0;
            double renderFps = frameDurationSeconds > 0 ? (1.0 / frameDurationSeconds) : -1.0;
            reporter.reportStatus(jobId, frameEnd, totalFrames, 100.0, renderFps, "RENDERING", blenderVer, true);
        }

        return renderedFilePaths;
    }
}
