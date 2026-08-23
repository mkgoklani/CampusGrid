import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * CAMPUS GRID - FRAME STITCHER & POST-PROCESSING UTILITY
 * 
 * Inspects completed job output directories, validates sequence continuity,
 * normalizes frame numbering (frame_0001.png), and compiles frames into
 * high-definition MP4 video animations using FFmpeg.
 */
public class FrameStitcher {

    private static final String DEFAULT_OUTPUT_DIR = "./output";
    private static final int DEFAULT_FPS = 30;

    private final Path baseOutputDir;

    public FrameStitcher() {
        this(Paths.get(DEFAULT_OUTPUT_DIR));
    }

    public FrameStitcher(Path baseOutputDir) {
        this.baseOutputDir = baseOutputDir;
    }

    /**
     * Executes the full post-processing pipeline for a completed job:
     * 1. Validates frame integrity and detects missing numbers.
     * 2. Normalizes filenames to standardized zero-padded format.
     * 3. Compiles the image sequence into an MP4 video file.
     *
     * @param jobId The unique job identifier.
     * @param totalFrames Expected total frame count.
     * @return Path to the compiled MP4 video if successful, or null if video compilation failed/skipped.
     */
    public Path processJobOutput(String jobId, int totalFrames) {
        return processJobOutput(jobId, totalFrames, DEFAULT_FPS);
    }

    public Path processJobOutput(String jobId, int totalFrames, int fps) {
        System.out.printf("[FRAME-STITCHER] ★ Starting post-processing for Job [%s] (%d frames expected)...\n",
            jobId, totalFrames);

        Path jobDir = baseOutputDir.resolve(jobId);
        if (!Files.exists(jobDir) || !Files.isDirectory(jobDir)) {
            System.err.printf("[FRAME-STITCHER-ERR] Directory not found: %s\n", jobDir.toAbsolutePath());
            return null;
        }

        // 1. Validate sequence integrity
        ValidationResult validation = validateFrameSequence(jobId, totalFrames);
        if (!validation.isValid()) {
            System.err.printf("[FRAME-STITCHER-WARN] ⚠ Sequence incomplete for [%s]. Missing frames: %s\n",
                jobId, validation.getMissingFrames());
        }

        // 2. Normalize and ensure zero-padded numbering (frame_%04d.png)
        normalizeFrameNames(jobDir);

        // 3. Compile to MP4 Video via FFmpeg
        String videoFileName = String.format("%s_animation.mp4", jobId);
        Path videoPath = jobDir.resolve(videoFileName);
        boolean compiled = compileToVideo(jobDir, fps, videoPath);

        if (compiled) {
            System.out.printf("[FRAME-STITCHER] ✔ Success! Master animation ready: %s\n", videoPath.toAbsolutePath());
            return videoPath;
        } else {
            System.out.printf("[FRAME-STITCHER] ℹ Raw image sequence preserved at: %s\n", jobDir.toAbsolutePath());
            return null;
        }
    }

    /**
     * Validates that all frames from 1 to totalFrames exist and are non-empty.
     */
    public ValidationResult validateFrameSequence(String jobId, int totalFrames) {
        Path jobDir = baseOutputDir.resolve(jobId);
        List<Integer> missingFrames = new ArrayList<>();
        int validCount = 0;

        if (!Files.exists(jobDir)) {
            for (int i = 1; i <= totalFrames; i++) missingFrames.add(i);
            return new ValidationResult(false, missingFrames, 0, totalFrames);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
            Set<Integer> detectedFrames = new HashSet<>();
            Pattern pattern = Pattern.compile("(?i)(?:frame_?|task_.*_)?(\\d+).*\\.(?:png|jpg|jpeg|bin)");

            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && Files.size(entry) > 0) {
                    Matcher matcher = pattern.matcher(entry.getFileName().toString());
                    if (matcher.find()) {
                        try {
                            int frameNum = Integer.parseInt(matcher.group(1));
                            detectedFrames.add(frameNum);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            for (int i = 1; i <= totalFrames; i++) {
                if (detectedFrames.contains(i)) {
                    validCount++;
                } else {
                    missingFrames.add(i);
                }
            }

        } catch (IOException e) {
            System.err.println("[FRAME-STITCHER-ERR] Error scanning directory: " + e.getMessage());
        }

        boolean isValid = missingFrames.isEmpty();
        return new ValidationResult(isValid, missingFrames, validCount, totalFrames);
    }

    /**
     * Normalizes image files into standardized sequential 4-digit zero-padded names: frame_0001.png
     */
    public void normalizeFrameNames(Path jobDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
            Pattern pattern = Pattern.compile("(?i)(?:frame_?|task_.*_)?(\\d+).*\\.(png|jpg|jpeg|bin)");

            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.endsWith(".mp4")) continue;

                Matcher matcher = pattern.matcher(name);
                if (matcher.find()) {
                    int frameNum = Integer.parseInt(matcher.group(1));
                    String ext = matcher.group(2).toLowerCase();
                    if ("bin".equals(ext)) ext = "png"; // Normalize binary image chunks to png

                    String normalizedName = String.format("frame_%04d.%s", frameNum, ext);
                    Path target = jobDir.resolve(normalizedName);

                    if (!file.equals(target)) {
                        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[FRAME-STITCHER-ERR] Error normalizing frame names: " + e.getMessage());
        }
    }

    /**
     * Invokes local FFmpeg via ProcessBuilder to encode image sequence into MP4.
     */
    public boolean compileToVideo(Path jobDir, int fps, Path outputVideoPath) {
        if (!isFFmpegInstalled()) {
            System.out.println("[FRAME-STITCHER-WARN] FFmpeg is not installed on this host. Skipping MP4 encoding.");
            return false;
        }

        System.out.printf("[FRAME-STITCHER] Invoking FFmpeg (Framerate: %d FPS)...\n", fps);

        List<String> command = List.of(
            "ffmpeg",
            "-y",                               // Overwrite output if exists
            "-framerate", String.valueOf(fps),  // Frame rate
            "-i", "frame_%04d.png",             // Input pattern
            "-c:v", "libx264",                  // H.264 video codec
            "-pix_fmt", "yuv420p",              // High compatibility pixel format
            outputVideoPath.getFileName().toString()
        );

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(jobDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();

            // Drain output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Suppress verbose logs unless critical
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 && Files.exists(outputVideoPath) && Files.size(outputVideoPath) > 0;

        } catch (Exception e) {
            System.err.println("[FRAME-STITCHER-ERR] FFmpeg compilation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if FFmpeg binary is available on the system PATH.
     */
    public static boolean isFFmpegInstalled() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================================================================
    // VALIDATION RESULT DTO
    // ========================================================================

    public static class ValidationResult {
        private final boolean valid;
        private final List<Integer> missingFrames;
        private final int validFrames;
        private final int totalExpected;

        public ValidationResult(boolean valid, List<Integer> missingFrames, int validFrames, int totalExpected) {
            this.valid = valid;
            this.missingFrames = missingFrames;
            this.validFrames = validFrames;
            this.totalExpected = totalExpected;
        }

        public boolean isValid() { return valid; }
        public List<Integer> getMissingFrames() { return missingFrames; }
        public int getValidFrames() { return validFrames; }
        public int getTotalExpected() { return totalExpected; }

        @Override
        public String toString() {
            return String.format("ValidationResult[Valid=%b, Count=%d/%d, Missing=%s]",
                valid, validFrames, totalExpected, missingFrames);
        }
    }
}
