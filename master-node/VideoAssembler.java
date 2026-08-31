import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Service to compile rendered PNG frames into an MP4 preview video using FFmpeg.
 */
public class VideoAssembler {

    private static final String DEFAULT_OUTPUT_DIR = "./output";

    /**
     * Stitches frames in input directory into an MP4 video.
     *
     * @param jobId Unique identifier of the job.
     * @param totalFrames Total frames expected.
     * @param fps Frame rate.
     * @return Path to the generated video, or null if failed.
     */
    public static Path assembleVideo(String jobId, int totalFrames, int fps) {
        Path jobDir = Paths.get(DEFAULT_OUTPUT_DIR).resolve(jobId);
        
        // Resolve Target Directory (checking for "frames" subdirectory first)
        Path framesDir = jobDir.resolve("frames");
        Path targetDir = Files.exists(framesDir) && Files.isDirectory(framesDir) ? framesDir : jobDir;

        // 1. Detect FFmpeg automatically
        System.out.println("[VIDEO] Searching FFmpeg...");
        String ffmpegCmd = FFmpegLocator.findExecutable();
        if (ffmpegCmd == null) {
            String errorMsg = "FFmpeg executable not found on the system. Tested PATH and common paths.";
            System.err.println("[VIDEO] " + errorMsg);
            writeErrorFile(jobDir, errorMsg);
            return null;
        }

        // 2. Gather all PNG files
        File[] files = targetDir.toFile().listFiles((dir, name) -> name.toLowerCase().endsWith(".png") && !name.startsWith("temp_frame_"));
        if (files == null || files.length == 0) {
            String errorMsg = "No PNG frames found in input directory: " + targetDir.toAbsolutePath();
            System.err.println("[VIDEO] " + errorMsg);
            writeErrorFile(jobDir, errorMsg);
            return null;
        }

        System.out.printf("[VIDEO] Found %d PNG frames\n", files.length);
        System.out.println("[VIDEO] Creating preview.mp4");

        // 3. Sort numerically by matching any digits in the filename
        Pattern digitPattern = Pattern.compile("(\\d+)");
        Arrays.sort(files, (f1, f2) -> {
            Matcher m1 = digitPattern.matcher(f1.getName());
            Matcher m2 = digitPattern.matcher(f2.getName());
            int val1 = m1.find() ? Integer.parseInt(m1.group(1)) : 0;
            int val2 = m2.find() ? Integer.parseInt(m2.group(1)) : 0;
            return Integer.compare(val1, val2);
        });

        // 4. Normalize files to temp sequential names so FFmpeg can stitch them regardless of gaps or names
        List<Path> tempFiles = new ArrayList<>();
        int seqNum = 1;
        for (File file : files) {
            Path src = file.toPath();
            Path dest = targetDir.resolve(String.format("temp_frame_%04d.png", seqNum++));
            try {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                tempFiles.add(dest);
            } catch (IOException e) {
                System.err.printf("[VIDEO-ASSEMBLER-ERR] Failed to copy temp frame %s: %s\n", src.getFileName(), e.getMessage());
            }
        }

        if (tempFiles.isEmpty()) {
            String errorMsg = "Failed to prepare any temp frames for FFmpeg.";
            System.err.println("[VIDEO] " + errorMsg);
            writeErrorFile(jobDir, errorMsg);
            return null;
        }

        // 5. Compile via FFmpeg
        String videoFileName = "preview.mp4";
        Path outputVideoPath = jobDir.resolve(videoFileName);
        
        boolean success = false;
        StringBuilder stderrLog = new StringBuilder();
        int exitCode = -1;

        try {
            List<String> command = List.of(
                ffmpegCmd,
                "-y",
                "-framerate", String.valueOf(fps > 0 ? fps : 30),
                "-i", "temp_frame_%04d.png",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                outputVideoPath.toAbsolutePath().toString()
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(targetDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            
            // Capture stderr/stdout
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrLog.append(line).append("\n");
                }
            }

            exitCode = process.waitFor();
            success = (exitCode == 0 && Files.exists(outputVideoPath) && Files.size(outputVideoPath) > 0);

        } catch (Exception e) {
            stderrLog.append("Exception: ").append(e.toString());
            System.err.println("[VIDEO] Process builder exception: " + e.getMessage());
        } finally {
            cleanupTempFiles(tempFiles);
        }

        if (success) {
            System.out.println("[VIDEO] Success");
            // Clear any error logs
            File errorFile = new File(jobDir.toFile(), "ffmpeg_error.txt");
            if (errorFile.exists()) {
                errorFile.delete();
            }
            return outputVideoPath;
        } else {
            System.err.println("[VIDEO] FFmpeg exit code: " + exitCode);
            System.err.println("[VIDEO] stderr:\n" + stderrLog.toString());
            System.err.println("[VIDEO] input directory: " + targetDir.toAbsolutePath());
            
            String diagnostic = String.format("FFmpeg exit code: %d\nStderr:\n%s\nInput Directory: %s",
                exitCode, stderrLog.toString(), targetDir.toAbsolutePath().toString());
            writeErrorFile(jobDir, diagnostic);
        }
        return null;
    }



    private static void writeErrorFile(Path jobDir, String errorMsg) {
        try {
            Files.createDirectories(jobDir);
            File errorFile = new File(jobDir.toFile(), "ffmpeg_error.txt");
            Files.writeString(errorFile.toPath(), errorMsg, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            System.err.println("[VIDEO] Failed to write error file: " + e.getMessage());
        }
    }

    private static void cleanupTempFiles(List<Path> tempFiles) {
        for (Path temp : tempFiles) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {}
        }
    }
}
