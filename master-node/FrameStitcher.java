import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CAMPUS GRID - FRAME STITCHER & POST-PROCESSING UTILITY
 * 
 * Inspects completed job output directories, validates sequence continuity,
 * normalizes frame numbering (frame_0001.png), packages frames into a downloadable
 * ZIP bundle ({jobId}_all_frames.zip), and compiles high-definition MP4 video animations.
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
     * 1. Normalizes filenames to standardized zero-padded format (frame_0001.png).
     * 2. Validates frame integrity and detects missing numbers.
     * 3. Creates a downloadable ZIP archive containing all rendered PNG frames.
     * 4. Compiles the image sequence into an MP4 video file if FFmpeg is available.
     *
     * @param jobId The unique job identifier.
     * @param totalFrames Expected total frame count.
     * @return Path to the compiled MP4 video if successful, or null if video compilation failed/skipped.
     */
    public Path processJobOutput(String jobId, int totalFrames) {
        return processJobOutput(jobId, totalFrames, DEFAULT_FPS, false);
    }

    public Path processJobOutput(String jobId, int totalFrames, int fps) {
        return processJobOutput(jobId, totalFrames, fps, false);
    }

    public Path processJobOutput(String jobId, int totalFrames, int fps, boolean deleteFramesAfterStitch) {
        System.out.printf("[FRAME-STITCHER] ★ Starting post-processing for Job [%s] (%d frames expected, cleanUp=%b)...\n",
            jobId, totalFrames, deleteFramesAfterStitch);

        Path jobDir = baseOutputDir.resolve(jobId);
        if (!Files.exists(jobDir) || !Files.isDirectory(jobDir)) {
            System.err.printf("[FRAME-STITCHER-ERR] Directory not found: %s\n", jobDir.toAbsolutePath());
            return null;
        }

        // 1. Unpack any video containers (e.g. .mkv/.avi) to discrete PNG frames if needed
        unpackVideoFramesIfPresent(jobDir);

        // 2. Normalize and ensure zero-padded numbering (frame_%04d.png)
        normalizeFrameNames(jobDir);

        // 3. Validate sequence integrity
        ValidationResult validation = validateFrameSequence(jobId, totalFrames);
        if (!validation.isValid()) {
            System.err.printf("[FRAME-STITCHER-WARN] ⚠ Sequence incomplete for [%s]. Missing frames: %s\n",
                jobId, validation.getMissingFrames());
        } else {
            System.out.printf("[FRAME-STITCHER] ✔ Sequence validated: all %d/%d frames intact.\n",
                validation.getValidFrames(), totalFrames);
        }

        // 3. Always create a ZIP bundle of all rendered frames for direct download
        Path zipPath = jobDir.resolve(String.format("%s_all_frames.zip", jobId));
        boolean zipped = createFramesZip(jobDir, zipPath);
        if (zipped) {
            System.out.printf("[FRAME-STITCHER] 📦 Packaged all frames into ZIP bundle: %s (Size: %d bytes)\n",
                zipPath.getFileName(), getFileSize(zipPath));
        }

        // 4. Compile to MP4 Video via FFmpeg if present
        String videoFileName = String.format("%s_animation.mp4", jobId);
        Path videoPath = jobDir.resolve(videoFileName);
        boolean compiled = compileToVideo(jobDir, fps, videoPath);

        if (compiled) {
            System.out.printf("[FRAME-STITCHER] ✔ Success! Master animation ready: %s\n", videoPath.toAbsolutePath());
            if (deleteFramesAfterStitch) {
                deleteRawFrames(jobDir, videoFileName, zipPath.getFileName().toString());
            }
            return videoPath;
        } else {
            System.out.printf("[FRAME-STITCHER] ℹ Raw image sequence & ZIP bundle preserved at: %s\n", jobDir.toAbsolutePath());
            return null;
        }
    }

    /**
     * Bundles all PNG frames in the job directory into a single ZIP archive.
     */
    public boolean createFramesZip(Path jobDir, Path outputZipPath) {
        try {
            List<Path> frameFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString().toLowerCase();
                    if (Files.isRegularFile(p) && name.endsWith(".png")) {
                        frameFiles.add(p);
                    }
                }
            }

            if (frameFiles.isEmpty()) {
                return false;
            }

            // Sort files in ascending order
            frameFiles.sort(Comparator.comparing(Path::getFileName));

            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(outputZipPath)))) {
                for (Path frame : frameFiles) {
                    ZipEntry entry = new ZipEntry(frame.getFileName().toString());
                    zos.putNextEntry(entry);
                    Files.copy(frame, zos);
                    zos.closeEntry();
                }
            }
            return true;
        } catch (IOException e) {
            System.err.printf("[FRAME-STITCHER-ERR] Failed creating ZIP archive for %s: %s\n",
                jobDir.getFileName(), e.getMessage());
            return false;
        }
    }

    private long getFileSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Stitches whatever frames are currently available for partial preview.
     */
    public Path stitchAvailableFrames(String jobId, int fps) {
        System.out.printf("[FRAME-STITCHER] Running partial/cancellation stitching for Job [%s]...\n", jobId);
        Path jobDir = baseOutputDir.resolve(jobId);
        if (!Files.exists(jobDir) || !Files.isDirectory(jobDir)) {
            return null;
        }

        normalizeFrameNames(jobDir);

        // Also create partial ZIP archive
        Path zipPath = jobDir.resolve(String.format("%s_all_frames.zip", jobId));
        createFramesZip(jobDir, zipPath);

        List<Path> frames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
            Pattern p = Pattern.compile("frame_(\\d+)\\.png");
            for (Path entry : stream) {
                if (p.matcher(entry.getFileName().toString()).matches()) {
                    frames.add(entry);
                }
            }
        } catch (IOException ignored) {}

        if (frames.isEmpty()) {
            return null;
        }

        frames.sort(Comparator.comparingInt(f -> {
            String name = f.getFileName().toString();
            return Integer.parseInt(name.replaceAll("[^0-9]", ""));
        }));

        List<Path> tempFiles = new ArrayList<>();
        boolean compiled = false;
        try {
            for (int i = 0; i < frames.size(); i++) {
                Path src = frames.get(i);
                Path dest = jobDir.resolve(String.format("temp_frame_%04d.png", i + 1));
                try {
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    tempFiles.add(dest);
                } catch (IOException ignored) {}
            }

            String videoFileName = String.format("%s_animation.mp4", jobId);
            Path videoPath = jobDir.resolve(videoFileName);
            
            if (isFFmpegInstalled() && !tempFiles.isEmpty()) {
                List<String> command = List.of(
                    getFFmpegExecutable(), "-y", "-framerate", String.valueOf(fps),
                    "-start_number", "1",
                    "-i", "temp_frame_%04d.png", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    videoFileName
                );
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(jobDir.toFile());
                pb.redirectErrorStream(true);
                try {
                    Process process = pb.start();
                    process.getInputStream().transferTo(OutputStream.nullOutputStream());
                    compiled = process.waitFor() == 0;
                } catch (Exception ignored) {}
            }
            return compiled ? videoPath : null;
        } finally {
            for (Path temp : tempFiles) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
        }
    }

    private void deleteRawFrames(Path jobDir, String preserveVideoName, String preserveZipName) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
            for (Path entry : stream) {
                String fname = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && !fname.equals(preserveVideoName) && !fname.equals(preserveZipName)) {
                    Files.deleteIfExists(entry);
                }
            }
            System.out.printf("[FRAME-STITCHER] 🗑 Cleaned up intermediate raw frame images in: %s\n", jobDir.getFileName());
        } catch (IOException e) {
            System.err.printf("[FRAME-STITCHER-WARN] Failed to delete raw frames: %s\n", e.getMessage());
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
                if (name.endsWith(".mp4") || name.endsWith(".zip")) continue;

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

        // Find all frames matching frame_(\d+).png
        List<Path> frames = new ArrayList<>();
        int minFrame = Integer.MAX_VALUE;
        int maxFrame = Integer.MIN_VALUE;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
            Pattern p = Pattern.compile("frame_(\\d+)\\.png");
            for (Path entry : stream) {
                Matcher m = p.matcher(entry.getFileName().toString());
                if (m.matches()) {
                    frames.add(entry);
                    int num = Integer.parseInt(m.group(1));
                    if (num < minFrame) minFrame = num;
                    if (num > maxFrame) maxFrame = num;
                }
            }
        } catch (IOException ignored) {}

        if (frames.isEmpty()) {
            System.err.println("[FRAME-STITCHER-WARN] No PNG frames found in " + jobDir);
            return false;
        }

        frames.sort(Comparator.comparingInt(f -> {
            String name = f.getFileName().toString();
            return Integer.parseInt(name.replaceAll("[^0-9]", ""));
        }));

        System.out.printf("[FRAME-STITCHER] Invoking FFmpeg for %d frame(s) (Range: %d-%d, Framerate: %d FPS)...\n",
            frames.size(), minFrame, maxFrame, fps);

        boolean hasGaps = (maxFrame - minFrame + 1) != frames.size();
        List<Path> tempFiles = new ArrayList<>();

        try {
            List<String> command = new ArrayList<>();
            command.add(getFFmpegExecutable());
            command.add("-y");
            command.add("-framerate");
            command.add(String.valueOf(fps));

            if (!hasGaps) {
                // Continuous sequence: specify -start_number directly
                command.add("-start_number");
                command.add(String.valueOf(minFrame));
                command.add("-i");
                command.add("frame_%04d.png");
            } else {
                // Sequence has gaps: create temporary contiguous aliases so FFmpeg encodes all available frames without stopping
                System.out.printf("[FRAME-STITCHER] Gaps detected in sequence (%d files across range %d-%d). Normalizing indices...\n",
                    frames.size(), minFrame, maxFrame);
                for (int i = 0; i < frames.size(); i++) {
                    Path src = frames.get(i);
                    Path dest = jobDir.resolve(String.format("temp_stitch_%04d.png", i + 1));
                    try {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        tempFiles.add(dest);
                    } catch (IOException ignored) {}
                }
                command.add("-start_number");
                command.add("1");
                command.add("-i");
                command.add("temp_stitch_%04d.png");
            }

            command.add("-c:v");
            command.add("libx264");
            command.add("-pix_fmt");
            command.add("yuv420p");
            command.add(outputVideoPath.getFileName().toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(jobDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Drain stdout
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 && Files.exists(outputVideoPath) && Files.size(outputVideoPath) > 0;

        } catch (Exception e) {
            System.err.println("[FRAME-STITCHER-ERR] FFmpeg compilation failed: " + e.getMessage());
            return false;
        } finally {
            for (Path temp : tempFiles) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Resolves the FFmpeg binary path using FFmpegLocator or system PATH.
     */
    public static String getFFmpegExecutable() {
        String located = FFmpegLocator.findExecutable();
        return (located != null && !located.trim().isEmpty()) ? located : "ffmpeg";
    }

    /**
     * Checks if FFmpeg binary is available on the system PATH or candidate directories.
     */
    public static boolean isFFmpegInstalled() {
        try {
            String exe = getFFmpegExecutable();
            Process process = new ProcessBuilder(exe, "-version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void unpackVideoFramesIfPresent(Path jobDir) {
        if (!isFFmpegInstalled()) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobDir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString().toLowerCase();
                if (name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".webm") || (name.endsWith(".mp4") && !name.contains("_animation.mp4"))) {
                    boolean hasPng = false;
                    try (DirectoryStream<Path> pngStream = Files.newDirectoryStream(jobDir, "*.png")) {
                        hasPng = pngStream.iterator().hasNext();
                    }
                    if (!hasPng) {
                        System.out.printf("[FRAME-STITCHER] Extracting discrete PNG frames from container %s...\n", file.getFileName());
                        List<String> command = List.of(
                            getFFmpegExecutable(), "-y", "-i", file.getFileName().toString(), "-start_number", "1", "frame_%04d.png"
                        );
                        ProcessBuilder pb = new ProcessBuilder(command);
                        pb.directory(jobDir.toFile());
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        process.getInputStream().transferTo(OutputStream.nullOutputStream());
                        process.waitFor();
                    }
                    break;
                }
            }
        } catch (Exception ignored) {}
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
