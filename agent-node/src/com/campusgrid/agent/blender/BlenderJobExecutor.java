package com.campusgrid.agent.blender;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a Blender render job in headless mode using ProcessBuilder.
 * Captures process output to track frame completions, update progress with FPS calculations,
 * and support runtime cancellation.
 */
public class BlenderJobExecutor {

    private static final Pattern FRAME_PATTERN = Pattern.compile("Fra:\\s*(\\d+)");
    private static final Pattern SAVED_PATTERN = Pattern.compile("Saved:\\s+['\"]?([^'\"]+)['\"]?");
    private static final Pattern SAMPLE_PATTERN = Pattern.compile("Sample\\s+(\\d+)/(\\d+)");
    
    // Tracks active Blender processes keyed by jobId to allow remote cancellation
    private static final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private static final Set<String> cancelledJobs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Safely terminates a running Blender process for a specific render job.
     *
     * @param jobId the unique identifier of the job to cancel.
     * @return true if a process was found and cancelled, false otherwise.
     */
    public static boolean cancelJob(String jobId) {
        if (jobId == null) return false;
        cancelledJobs.add(jobId);
        Process process = activeProcesses.get(jobId);
        if (process != null) {
            System.out.println("[EXECUTOR] Cancelling Blender process for job: " + jobId);
            
            // Send SIGINT first (graceful) — Blender flushes and saves a partial frame on SIGINT
            // instead of SIGKILL which discards the buffer entirely
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    // Windows: execute taskkill to kill process tree cleanly
                    Runtime.getRuntime().exec(new String[]{
                        "taskkill", "/F", "/T", "/PID", 
                        String.valueOf(process.toHandle().pid())
                    });
                } else {
                    // Unix (macOS / Linux): send SIGINT so Blender flushes its current tile buffer
                    Runtime.getRuntime().exec(new String[]{
                        "kill", "-INT", 
                        String.valueOf(process.toHandle().pid())
                    });
                    // Give Blender up to 1 second to write the partial frame
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                System.err.println("[EXECUTOR] OS process signal failed, falling back to force kill: " + e.getMessage());
            }
            
            // Now force kill if still running
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            return true;
        }
        return false;
    }

    /**
     * Executes the given Blender render job using the global GPU preference.
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
        return executeJob(jobId, blendFilePath, frameStart, frameEnd, outputDir, renderEngine, com.campusgrid.agent.network.PayloadListener.useGpu, reporter);
    }

    /**
     * Executes the given Blender render job with explicit GPU/CPU device acceleration selection.
     * Launches Blender in headless mode and reports progress frame-by-frame.
     */
    public static List<String> executeJob(
            String jobId,
            String blendFilePath,
            int frameStart,
            int frameEnd,
            String outputDir,
            String renderEngine,
            boolean useGpu,
            ProgressReporter reporter
    ) throws Exception {

        String blenderPath = BlenderUtils.findExecutablePath();
        if (blenderPath == null) {
            throw new IllegalStateException("Blender 3D executable not found on host. Please install Blender on this worker node.");
        }

        File blendFile = (blendFilePath != null) ? new File(blendFilePath) : new File("scene.blend");
        if (!blendFile.exists()) {
            throw new FileNotFoundException("Blend file not found on host: " + blendFile.getAbsolutePath() + ". Please upload or specify a valid .blend file.");
        }

        // Calculate total frames
        int totalFrames = Math.max(1, frameEnd - frameStart + 1);

        System.out.printf("[EXECUTOR] Running Blender Headless Render on File: %s (Size: %d bytes, GPU Acceleration: %b)\n",
            blendFile.getAbsolutePath(), blendFile.length(), useGpu);

        // Build command
        List<String> command = new ArrayList<>();
        command.add(blenderPath);
        command.add("-b"); // headless mode
        command.add(blendFile.getAbsolutePath());

        String engine = (renderEngine != null && !renderEngine.trim().isEmpty()) ? renderEngine.trim() : "CYCLES";
        if ("BLENDER_EEVEE".equalsIgnoreCase(engine)) {
            String ver = BlenderInstaller.getInstallationStatus().getVersion();
            if (ver != null && (ver.startsWith("4.2") || ver.startsWith("4.3") || ver.startsWith("4.4") || ver.startsWith("5."))) {
                engine = "BLENDER_EEVEE_NEXT";
            }
        }

        // Configure GPU / CPU compute device in Blender via single-line Python script (Windows safe)
        if (useGpu) {
            System.out.println("[EXECUTOR] Activating GPU Acceleration backend (METAL / CUDA / OPTIX / HIP / ONEAPI)...");
            String pyGpu = String.format(
                "import bpy; " +
                "s = bpy.context.scene; " +
                "(s and setattr(s.render, 'engine', '%s')); " +
                "prefs = bpy.context.preferences.addons.get('cycles'); " +
                "cprefs = prefs.preferences if prefs else None; " +
                "(cprefs and cprefs.get_devices()); " +
                "[(setattr(d,'use',True) if d.type in ('OPTIX','CUDA','HIP','METAL','ONEAPI') else None) for d in (cprefs.devices if cprefs else [])]; " +
                "best_type = next((t for t in ('OPTIX','CUDA','HIP','METAL','ONEAPI') if any(d.type==t for d in (cprefs.devices if cprefs else []))), None); " +
                "(best_type and setattr(cprefs,'compute_device_type',best_type)); " +
                "(best_type and getattr(s, 'cycles', None) and setattr(s.cycles, 'device', 'GPU'))",
                engine
            );
            command.add("--python-expr");
            command.add(pyGpu);
        } else {
            System.out.println("[EXECUTOR] Disabling GPU Acceleration — using standard multi-core CPU compute...");
            String pyCpu = String.format(
                "import bpy; " +
                "s = bpy.context.scene; " +
                "(s and setattr(s.render, 'engine', '%s')); " +
                "(getattr(s, 'cycles', None) and setattr(s.cycles, 'device', 'CPU')); " +
                "prefs = bpy.context.preferences.addons.get('cycles'); " +
                "cprefs = prefs.preferences if prefs else None; " +
                "(cprefs and setattr(cprefs,'compute_device_type','NONE'))",
                engine
            );
            command.add("--python-expr");
            command.add(pyCpu);
        }

        File outDirFile = new File((outputDir != null && !outputDir.trim().isEmpty()) ? outputDir : "./output/" + jobId);
        if (!outDirFile.exists()) {
            outDirFile.mkdirs();
        }
        String outPattern = new File(outDirFile, "frame_####").getAbsolutePath();
        command.add("-o");
        command.add(outPattern);

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

                // ── INTRA-FRAME PROGRESS (Cycles "Sample X/Y") ──
                // Blender reports samples continuously during Cycles rendering.
                // Use these to provide live progress *within* the current frame.
                Matcher sampleMatcher = SAMPLE_PATTERN.matcher(line);
                if (sampleMatcher.find() && reporter != null) {
                    try {
                        int samplesNow = Integer.parseInt(sampleMatcher.group(1));
                        int samplesTotal = Integer.parseInt(sampleMatcher.group(2));
                        if (samplesTotal > 0) {
                            double frameProgress = (double) samplesNow / samplesTotal;
                            int framesPosition = Math.max(completedFrames, lastSeenFrame - frameStart);
                            double percentage = ((framesPosition + frameProgress) / totalFrames) * 100.0;
                            percentage = Math.min(99.9, percentage);
                            reporter.reportStatus(jobId, lastSeenFrame, totalFrames, percentage, -1.0, "RENDERING", blenderVer, false);
                        }
                    } catch (NumberFormatException ignored) {}
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
                        reporter.reportStatus(jobId, lastSeenFrame, totalFrames, percentage, renderFps, "RENDERING", blenderVer, true);
                    }
                }
            }
        } finally {
            activeProcesses.remove(jobId);
        }

        int exitCode = process.waitFor();
        System.out.println("[EXECUTOR] Blender process exited with code: " + exitCode);
        
        if (cancelledJobs.remove(jobId) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Render job " + jobId + " was cancelled");
        }

        if (exitCode != 0) {
            if (useGpu) {
                System.out.printf("[EXECUTOR-RETRY] ⚠ GPU compute execution failed with exit code %d (e.g. Device OOM or Driver issue). Retrying on CPU compute mode...\n", exitCode);
                return executeJob(jobId, blendFilePath, frameStart, frameEnd, outputDir, renderEngine, false, reporter);
            } else {
                throw new RuntimeException("Blender rendering failed with exit code " + exitCode + " for job [" + jobId + "]. Check scene configuration or Blender logs.");
            }
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
