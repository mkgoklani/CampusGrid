import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/**
 * Handles automated detection, verification, and standalone installation of FFmpeg on the Master Node.
 * Ensures the host is always equipped to stitch and encode animation frames into MP4 video.
 */
public class FFmpegInstaller {

    private static volatile String cachedExecutablePath = null;
    private static volatile boolean isInstalling = false;

    public interface ProgressCallback {
        void onProgress(double percent, String message);
    }

    /**
     * Finds the absolute path to the FFmpeg executable, or returns null if not found.
     */
    public static synchronized String getExecutablePath() {
        if (cachedExecutablePath != null && new File(cachedExecutablePath).canExecute()) {
            return cachedExecutablePath;
        }

        // 1. Check system PATH
        if (testCommand("ffmpeg", "-version")) {
            cachedExecutablePath = "ffmpeg";
            return "ffmpeg";
        }

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String localApp = System.getenv("LOCALAPPDATA");
            String workDir;
            try {
                workDir = new File(".").getCanonicalPath();
            } catch (Exception e) {
                workDir = System.getProperty("user.dir");
            }

            String[] winPaths = {
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\ffmpeg\\ffmpeg.exe",
                "C:\\Program Files\\FFmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\FFmpeg\\ffmpeg.exe",
                workDir + "\\ffmpeg_bin\\bin\\ffmpeg.exe",
                workDir + "\\ffmpeg_bin\\ffmpeg.exe",
                (localApp != null) ? localApp + "\\Programs\\FFmpeg\\bin\\ffmpeg.exe" : "",
                (localApp != null) ? localApp + "\\Programs\\FFmpeg\\ffmpeg.exe" : ""
            };

            for (String p : winPaths) {
                if (p != null && !p.isEmpty()) {
                    File f = new File(p);
                    if (f.exists() && f.canExecute()) {
                        cachedExecutablePath = f.getAbsolutePath();
                        return cachedExecutablePath;
                    }
                }
            }

            // Deep scan in known base folders
            File[] baseDirs = {
                new File("C:\\ffmpeg"),
                new File(workDir, "ffmpeg_bin"),
                new File("C:\\Program Files\\FFmpeg"),
                (localApp != null) ? new File(localApp, "Programs\\FFmpeg") : null
            };
            for (File baseDir : baseDirs) {
                if (baseDir != null && baseDir.exists() && baseDir.isDirectory()) {
                    File found = findExecutable(baseDir, "ffmpeg.exe", 3);
                    if (found != null && found.canExecute()) {
                        cachedExecutablePath = found.getAbsolutePath();
                        return cachedExecutablePath;
                    }
                }
            }
        } else {
            // Linux / macOS
            String[] unixPaths = {
                "/usr/bin/ffmpeg",
                "/usr/local/bin/ffmpeg",
                "/opt/homebrew/bin/ffmpeg",
                new File("./ffmpeg_bin/ffmpeg").getAbsolutePath()
            };
            for (String p : unixPaths) {
                File f = new File(p);
                if (f.exists() && f.canExecute()) {
                    cachedExecutablePath = f.getAbsolutePath();
                    return cachedExecutablePath;
                }
            }
        }

        return null;
    }

    public static boolean isInstalled() {
        return getExecutablePath() != null;
    }

    /**
     * Ensures FFmpeg is installed and ready. If missing, automatically downloads and extracts it.
     */
    public static synchronized String ensureInstalled() {
        return ensureInstalled((pct, msg) -> {
            System.out.printf("[FFMPEG-INSTALL] [%.0f%%] %s\n", pct, msg);
        });
    }

    public static synchronized String ensureInstalled(ProgressCallback callback) {
        String existing = getExecutablePath();
        if (existing != null) {
            return existing;
        }

        if (isInstalling) {
            System.out.println("[FFMPEG-INSTALL] Installation already in progress. Waiting...");
            for (int i = 0; i < 60; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                String found = getExecutablePath();
                if (found != null) return found;
                if (!isInstalling) break;
            }
            return getExecutablePath();
        }

        isInstalling = true;
        try {
            System.out.println("[FFMPEG-INSTALL] FFmpeg not found on Master. Starting automated installation...");
            if (callback != null) callback.onProgress(5.0, "Starting FFmpeg installation pipeline...");

            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                return installOnWindows(callback);
            } else if (os.contains("mac")) {
                return installOnMac(callback);
            } else {
                return installOnLinux(callback);
            }
        } finally {
            isInstalling = false;
        }
    }

    private static String installOnWindows(ProgressCallback callback) {
        File destDir = determineInstallDir();
        System.out.println("[FFMPEG-INSTALL] Target install directory: " + destDir.getAbsolutePath());
        if (callback != null) callback.onProgress(10.0, "Target install path: " + destDir.getAbsolutePath());

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "CampusGrid");
        if (!tempDir.exists()) tempDir.mkdirs();
        File zipFile = new File(tempDir, "ffmpeg-win64.zip");

        String primaryUrl = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
        String fallbackUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";

        boolean downloaded = false;

        // Check cached zip
        if (zipFile.exists() && zipFile.length() > 50_000_000L) {
            System.out.println("[FFMPEG-INSTALL] Using previously downloaded archive: " + zipFile.getAbsolutePath());
            if (callback != null) callback.onProgress(60.0, "Using cached FFmpeg archive package...");
            downloaded = true;
        } else {
            System.out.println("[FFMPEG-INSTALL] Downloading FFmpeg from primary source: " + primaryUrl);
            if (callback != null) callback.onProgress(15.0, "Downloading FFmpeg standalone suite...");
            downloaded = downloadFile(primaryUrl, zipFile, callback, 15.0, 75.0);

            if (!downloaded) {
                System.out.println("[FFMPEG-INSTALL] Primary source failed. Falling back to: " + fallbackUrl);
                if (callback != null) callback.onProgress(20.0, "Connecting to fallback mirror (gyan.dev)...");
                downloaded = downloadFile(fallbackUrl, zipFile, callback, 20.0, 75.0);
            }
        }

        if (!downloaded || !zipFile.exists() || zipFile.length() < 20_000_000L) {
            System.err.println("[FFMPEG-INSTALL-ERR] Failed downloading FFmpeg archive.");
            if (callback != null) callback.onProgress(-1.0, "FFmpeg download failed.");
            return null;
        }

        // Extraction
        if (callback != null) callback.onProgress(80.0, "Extracting FFmpeg to " + destDir.getAbsolutePath() + "...");
        System.out.println("[FFMPEG-INSTALL] Extracting archive to: " + destDir.getAbsolutePath());

        boolean extracted = false;
        // Native tar extraction (bsdtar on Windows 10/11)
        if (testCommand("tar", "--help")) {
            try {
                System.out.println("[FFMPEG-INSTALL] Using native tar for high-speed extraction...");
                ProcessBuilder pb = new ProcessBuilder("tar", "-xf", zipFile.getAbsolutePath(), "-C", destDir.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().transferTo(OutputStream.nullOutputStream());
                extracted = (p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0);
            } catch (Exception ignored) {}
        }

        if (!extracted) {
            System.out.println("[FFMPEG-INSTALL] Running Java extraction...");
            extractZip(zipFile, destDir, callback, 80.0, 95.0);
        }

        // Search for ffmpeg.exe
        File ffmpegExe = findExecutable(destDir, "ffmpeg.exe", 4);
        if (ffmpegExe != null && ffmpegExe.canExecute()) {
            cachedExecutablePath = ffmpegExe.getAbsolutePath();
            if (callback != null) callback.onProgress(100.0, "FFmpeg installed successfully at: " + cachedExecutablePath);
            System.out.println("[FFMPEG-INSTALL] ✔ FFmpeg ready: " + cachedExecutablePath);
            return cachedExecutablePath;
        }

        System.err.println("[FFMPEG-INSTALL-ERR] Extraction completed but ffmpeg.exe could not be found.");
        return null;
    }

    private static String installOnLinux(ProgressCallback callback) {
        if (testCommand("which", "apt-get")) {
            try {
                if (callback != null) callback.onProgress(30.0, "Attempting system package installation...");
                ProcessBuilder pb = new ProcessBuilder("sudo", "apt-get", "update", "-y");
                pb.start().waitFor();
                ProcessBuilder pb2 = new ProcessBuilder("sudo", "apt-get", "install", "-y", "ffmpeg");
                pb2.start().waitFor();
                String path = getExecutablePath();
                if (path != null) return path;
            } catch (Exception ignored) {}
        }

        // Standalone static build
        try {
            File destDir = new File("./ffmpeg_bin");
            destDir.mkdirs();
            File tarFile = new File("./downloads/ffmpeg-linux.tar.xz");
            tarFile.getParentFile().mkdirs();
            String url = "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";
            if (callback != null) callback.onProgress(20.0, "Downloading static Linux FFmpeg binary...");
            downloadFile(url, tarFile, callback, 20.0, 70.0);

            ProcessBuilder pb = new ProcessBuilder("tar", "-xf", tarFile.getAbsolutePath(), "-C", destDir.getAbsolutePath(), "--strip-components=1");
            pb.start().waitFor();
            File exe = new File(destDir, "ffmpeg");
            if (exe.exists()) {
                exe.setExecutable(true);
                cachedExecutablePath = exe.getAbsolutePath();
                return cachedExecutablePath;
            }
        } catch (Exception e) {
            System.err.println("[FFMPEG-INSTALL-ERR] Linux static install failed: " + e.getMessage());
        }
        return null;
    }

    private static String installOnMac(ProgressCallback callback) {
        if (testCommand("which", "brew")) {
            try {
                if (callback != null) callback.onProgress(30.0, "Installing via Homebrew...");
                ProcessBuilder pb = new ProcessBuilder("brew", "install", "ffmpeg");
                pb.start().waitFor();
                String path = getExecutablePath();
                if (path != null) return path;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static File determineInstallDir() {
        File cFfmpeg = new File("C:\\ffmpeg");
        if (testWritableDir(cFfmpeg)) {
            return cFfmpeg;
        }
        File progFiles = new File("C:\\Program Files\\FFmpeg");
        if (testWritableDir(progFiles)) {
            return progFiles;
        }
        File localBin = new File("./ffmpeg_bin").getAbsoluteFile();
        localBin.mkdirs();
        return localBin;
    }

    private static boolean testWritableDir(File dir) {
        try {
            if (!dir.exists()) {
                if (!dir.mkdirs()) return false;
            }
            File testProbe = new File(dir, ".probe_" + System.currentTimeMillis() + ".tmp");
            if (testProbe.createNewFile()) {
                testProbe.delete();
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean downloadFile(String urlStr, File destFile, ProgressCallback callback, double startPct, double endPct) {
        try {
            int maxRedirects = 10;
            String currentUrl = urlStr;

            for (int i = 0; i < maxRedirects; i++) {
                URL url = URI.create(currentUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CampusGrid-FFmpegInstaller/1.0");
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(60_000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    currentUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (currentUrl == null) return false;
                    continue;
                }
                if (code != 200) {
                    System.err.println("[FFMPEG-INSTALL-ERR] HTTP " + code + " for " + currentUrl);
                    return false;
                }

                long totalBytes = conn.getContentLengthLong();
                long downloadedBytes = 0;

                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 131072);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[131072];
                    int read;
                    long lastReport = System.currentTimeMillis();
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloadedBytes += read;
                        long now = System.currentTimeMillis();
                        if (now - lastReport > 600 && totalBytes > 0) {
                            lastReport = now;
                            double fraction = (double) downloadedBytes / totalBytes;
                            double pct = startPct + fraction * (endPct - startPct);
                            if (callback != null) {
                                callback.onProgress(pct, String.format("Downloading FFmpeg: %.1f / %.1f MB",
                                    downloadedBytes / 1_048_576.0, totalBytes / 1_048_576.0));
                            }
                        }
                    }
                }
                conn.disconnect();
                return destFile.exists() && destFile.length() > 5_000_000L;
            }
        } catch (Exception e) {
            System.err.println("[FFMPEG-INSTALL-ERR] Download exception: " + e.getMessage());
        }
        return false;
    }

    private static void extractZip(File zipFile, File destDir, ProgressCallback callback, double startPct, double endPct) {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile), 131072))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            int count = 0;
            long lastReport = System.currentTimeMillis();
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                String name = entry.getName();
                File newFile = new File(destDir, name);
                if (!newFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new SecurityException("Zip slip detected: " + name);
                }
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    }
                }
                zis.closeEntry();
                long now = System.currentTimeMillis();
                if (now - lastReport > 800) {
                    lastReport = now;
                    double currentPct = Math.min(endPct, startPct + (count % 300) * 0.05);
                    if (callback != null) callback.onProgress(currentPct, "Extracting FFmpeg files (" + count + " items)...");
                }
            }
        } catch (Exception e) {
            System.err.println("[FFMPEG-INSTALL-ERR] Zip extraction error: " + e.getMessage());
        }
    }

    private static File findExecutable(File root, String fileName, int maxDepth) {
        if (root == null || !root.exists() || maxDepth < 0) return null;
        File direct = new File(root, fileName);
        if (direct.exists() && direct.canExecute()) return direct;

        File[] list = root.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile() && f.getName().equalsIgnoreCase(fileName) && f.canExecute()) {
                    return f;
                }
                if (f.isDirectory() && maxDepth > 0) {
                    File nested = findExecutable(f, fileName, maxDepth - 1);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
    }

    private static boolean testCommand(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
