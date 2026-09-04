package com.campusgrid.agent.blender;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Handles detection, verification, and automated standalone installation of Blender 3D.
 * Supports macOS, Linux, and Windows with package manager discovery and pure Java/curl direct CDN fallback.
 */
public class BlenderInstaller {

    /**
     * Represents the installation status of Blender on the host machine.
     */
    public static class Status {
        private final boolean installed;
        private final String version;
        private final String executablePath;

        public Status(boolean installed, String version, String executablePath) {
            this.installed = installed;
            this.version = version;
            this.executablePath = executablePath;
        }

        public boolean isInstalled() {
            return installed;
        }

        public String getVersion() {
            return version;
        }

        public String getExecutablePath() {
            return executablePath;
        }

        @Override
        public String toString() {
            return String.format("BlenderStatus[Installed=%b, Version=%s, Path=%s]", 
                installed, version, executablePath);
        }
    }

    public interface ProgressCallback {
        void onProgress(double percent, String message);
    }

    public static volatile double currentInstallProgress = -1.0;
    public static volatile boolean isInstalling = false;

    /**
     * Detects if Blender is installed on the system, checks its version, and returns its status.
     * Searches system PATH, standard directories, and local portable ./blender_bin paths.
     */
    public static Status getInstallationStatus() {
        String path = BlenderUtils.findExecutablePath();
        if (path == null) {
            return new Status(false, "Unknown", null);
        }

        String output = BlenderUtils.executeCommand(path, "--version");
        if (output == null || output.isEmpty()) {
            output = BlenderUtils.executeCommand(path, "-v");
        }

        if (output == null || output.isEmpty()) {
            return new Status(false, "Unknown", path);
        }

        String version = BlenderUtils.parseVersion(output);
        boolean installed = !"Unknown".equals(version);

        return new Status(installed, version, path);
    }

    /**
     * Automatically downloads/installs Blender on the host machine with live progress reporting.
     * Compatible with macOS, Linux, and Windows.
     */
    public static synchronized boolean installBlender(ProgressCallback callback) {
        return installBlender("", callback);
    }

    public static synchronized boolean installBlender(String downloadUrl, ProgressCallback callback) {
        try {
            System.out.println("[DIAG] OS: " + System.getProperty("os.name"));
            System.out.println("[DIAG] Arch: " + System.getProperty("os.arch"));
            System.out.println("[DIAG] Working dir: " + new File(".").getCanonicalPath());
            System.out.println("[DIAG] user.dir: " + System.getProperty("user.dir"));
            System.out.println("[DIAG] Custom download URL: " + downloadUrl);
        } catch (Exception ignored) {}

        if (isInstalling) {
            System.out.println("[INSTALLER] Installation already in progress...");
            return false;
        }

        Status current = getInstallationStatus();
        if (current.isInstalled()) {
            System.out.println("[INSTALLER] Blender is already installed: " + current.getVersion());
            if (callback != null) callback.onProgress(100.0, "Blender is already installed (" + current.getVersion() + ")");
            return true;
        }

        isInstalling = true;
        currentInstallProgress = 5.0;
        if (callback != null) callback.onProgress(5.0, "Initiating Blender automated installation pipeline...");

        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) {
                return installOnMac(downloadUrl, callback);
            } else if (os.contains("win")) {
                return installOnWindows(downloadUrl, callback);
            } else {
                return installOnLinux(downloadUrl, callback);
            }
        } finally {
            isInstalling = false;
            currentInstallProgress = -1.0;
        }
    }


    private static boolean installOnMac(String downloadUrl, ProgressCallback callback) {
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            System.out.println("[INSTALLER] Running direct offline Mac DMG download from: " + downloadUrl);
            return directDownloadMacDMG(downloadUrl, callback);
        }
        System.out.println("[INSTALLER] macOS detected. Checking for Homebrew...");
        String brewPath = findBrewPath();

        // 1. If Homebrew is missing, attempt to bootstrap Homebrew automatically
        if (brewPath == null) {
            if (callback != null) callback.onProgress(10.0, "Homebrew not found. Bootstrapping package manager...");
            System.out.println("[INSTALLER] Homebrew not found. Attempting non-interactive Homebrew installation...");
            try {
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", 
                    "NONINTERACTIVE=1 /bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                proc.waitFor();
                brewPath = findBrewPath();
            } catch (Exception ignored) {}
        }

        // 2. If Homebrew is available, run brew install --cask blender
        if (brewPath != null) {
            try {
                if (callback != null) callback.onProgress(20.0, "Running Homebrew to install Blender 3D suite...");
                System.out.println("[INSTALLER] Running: " + brewPath + " install --cask blender");
                ProcessBuilder pb = new ProcessBuilder(brewPath, "install", "--cask", "blender");
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    double currentPct = 25.0;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[INSTALLER-LOG] " + line);
                        if (line.contains("Downloading") || line.contains("Fetching")) {
                            currentPct = Math.min(65.0, currentPct + 8.0);
                            if (callback != null) callback.onProgress(currentPct, "Downloading Blender release package...");
                        } else if (line.contains("Installing") || line.contains("Moving") || line.contains("Linking")) {
                            currentPct = Math.min(90.0, currentPct + 10.0);
                            if (callback != null) callback.onProgress(currentPct, "Mounting DMG and installing Blender to /Applications...");
                        }
                    }
                }

                proc.waitFor();
                if (callback != null) callback.onProgress(95.0, "Verifying installed Blender binary...");
                Thread.sleep(1000);

                Status verified = getInstallationStatus();
                if (verified.isInstalled()) {
                    if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                    System.out.println("[INSTALLER] ✔ Blender installed: " + verified.getExecutablePath());
                    return true;
                }
            } catch (Exception e) {
                System.err.println("[INSTALLER-ERR] Homebrew installation exception: " + e.getMessage());
            }
        }

        // 3. Fallback: Direct Official CDN DMG Download & Extraction (Zero package managers needed)
        System.out.println("[INSTALLER] Running direct standalone DMG download fallback...");
        return directDownloadMacDMG(callback);
    }

    private static String findBrewPath() {
        String[] possible = {
            "/opt/homebrew/bin/brew",
            "/usr/local/bin/brew",
            System.getProperty("user.home") + "/.homebrew/bin/brew"
        };
        for (String p : possible) {
            File f = new File(p);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        String which = BlenderUtils.executeCommand("which", "brew");
        if (which != null && !which.trim().isEmpty() && new File(which.trim()).canExecute()) {
            return which.trim();
        }
        return null;
    }

    private static boolean directDownloadMacDMG(ProgressCallback callback) {
        return directDownloadMacDMG("", callback);
    }

    private static boolean directDownloadMacDMG(String downloadUrl, ProgressCallback callback) {
        try {
            File tempDir = new File("./downloads");
            if (!tempDir.exists()) tempDir.mkdirs();
            File dmgFile = new File(tempDir, "blender_macos.dmg");

            boolean isArm = System.getProperty("os.arch").toLowerCase().contains("aarch64") 
                         || System.getProperty("os.arch").toLowerCase().contains("arm");
            String url = (downloadUrl != null && !downloadUrl.isEmpty())
                ? (downloadUrl.contains("?") ? downloadUrl + "&arch=" + (isArm ? "arm64" : "x64") : downloadUrl + "?arch=" + (isArm ? "arm64" : "x64"))
                : (isArm 
                    ? "https://download.blender.org/release/Blender5.1/blender-5.1.2-macos-arm64.dmg"
                    : "https://download.blender.org/release/Blender5.1/blender-5.1.2-macos-x64.dmg");

            String whichCurl = BlenderUtils.executeCommand("which", "curl");
            boolean downloaded = false;
            if (whichCurl != null && !whichCurl.isEmpty()) {
                if (callback != null) callback.onProgress(15.0, "Downloading Blender 4.2 official DMG (~300MB)...");
                System.out.println("[INSTALLER] Downloading Blender DMG via curl: " + url);
                BlenderUtils.executeCommandWithTimeout(300, whichCurl.trim(), "-L", "-o", dmgFile.getAbsolutePath(), url);
                downloaded = dmgFile.exists() && dmgFile.length() > 50000000; // at least 50MB
            }

            if (!downloaded) {
                if (callback != null) callback.onProgress(20.0, "Downloading Blender DMG via Java HTTP streaming...");
                downloaded = downloadFileWithProgress(url, dmgFile, callback, 20.0, 75.0);
            }

            if (!downloaded) {
                System.err.println("[INSTALLER-ERR] Failed downloading Blender DMG");
                return false;
            }

            if (callback != null) callback.onProgress(80.0, "Mounting Blender DMG image...");
            System.out.println("[INSTALLER] Attaching DMG: " + dmgFile.getAbsolutePath());
            BlenderUtils.executeCommandWithTimeout(120, "hdiutil", "attach", dmgFile.getAbsolutePath(), "-nobrowse", "-readonly");

            // Look for mounted volume under /Volumes
            File volumesDir = new File("/Volumes");
            File mountedApp = null;
            File mountedVol = null;

            if (volumesDir.exists() && volumesDir.isDirectory()) {
                File[] list = volumesDir.listFiles();
                if (list != null) {
                    for (File v : list) {
                        if (v.getName().toLowerCase().contains("blender")) {
                            File app = new File(v, "Blender.app");
                            if (app.exists()) {
                                mountedApp = app;
                                mountedVol = v;
                                break;
                            }
                        }
                    }
                }
            }

            if (mountedApp == null) {
                mountedApp = new File("/Volumes/Blender/Blender.app");
                mountedVol = new File("/Volumes/Blender");
            }

            if (mountedApp.exists()) {
                if (callback != null) callback.onProgress(88.0, "Installing Blender.app to /Applications...");
                System.out.println("[INSTALLER] Copying " + mountedApp.getAbsolutePath() + " to /Applications/...");
                
                // Copy to /Applications, fallback to ~/Applications
                String targetApp = "/Applications/Blender.app";
                String cpOut = BlenderUtils.executeCommand("cp", "-R", mountedApp.getAbsolutePath(), "/Applications/");
                
                File targetFile = new File(targetApp);
                if (!targetFile.exists()) {
                    String userApp = System.getProperty("user.home") + "/Applications/Blender.app";
                    BlenderUtils.executeCommand("cp", "-R", mountedApp.getAbsolutePath(), System.getProperty("user.home") + "/Applications/");
                    targetApp = userApp;
                }

                // Strip quarantine attribute so macOS allows execution without popup
                BlenderUtils.executeCommand("xattr", "-dr", "com.apple.quarantine", targetApp);
            }

            if (callback != null) callback.onProgress(95.0, "Detaching DMG image...");
            if (mountedVol != null && mountedVol.exists()) {
                BlenderUtils.executeCommand("hdiutil", "detach", mountedVol.getAbsolutePath(), "-force");
            }

            Status verified = getInstallationStatus();
            if (verified.isInstalled()) {
                if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                System.out.println("[INSTALLER] ✔ Blender installed successfully: " + verified.getExecutablePath());
                return true;
            }
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Direct DMG installation error: " + e.getMessage());
        }
        return false;
    }

    private static boolean installOnLinux(ProgressCallback callback) {
        return installOnLinux("", callback);
    }

    private static boolean installOnLinux(String downloadUrl, ProgressCallback callback) {
        // Direct Official Portable tar.xz download (avoids sudo apt-get password hangs in headless nodes)
        System.out.println("[INSTALLER] Running direct standalone Linux tar.xz download...");
        try {
            File binDir = new File("./blender_bin");
            if (!binDir.exists()) binDir.mkdirs();
            File tarFile = new File("./downloads/blender-linux.tar.xz");
            if (!tarFile.getParentFile().exists()) tarFile.getParentFile().mkdirs();

            String url = (downloadUrl != null && !downloadUrl.isEmpty())
                ? downloadUrl
                : "https://download.blender.org/release/Blender5.1/blender-5.1.2-linux-x64.tar.xz";
            if (callback != null) callback.onProgress(20.0, "Downloading standalone Blender archive...");
            downloadFileWithProgress(url, tarFile, callback, 20.0, 65.0);

            if (callback != null) callback.onProgress(70.0, "Extracting portable Blender (may take several minutes)...");
            // Allow up to 5 minutes (300 seconds) for heavy decompression
            BlenderUtils.executeCommandWithTimeout(300, "tar", "-xf", tarFile.getAbsolutePath(), "-C", binDir.getAbsolutePath(), "--strip-components=1");
            
            // After extraction, explicitly check the expected path first
            File blenderExe = new File(binDir, "blender");
            if (blenderExe.exists()) {
                blenderExe.setExecutable(true);
                System.out.println("[INSTALLER] Binary found at: " + blenderExe.getAbsolutePath());
            }

            // Give the filesystem a moment, then retry verification up to 3x
            for (int attempt = 0; attempt < 3; attempt++) {
                Thread.sleep(1000);
                Status verified = getInstallationStatus();
                if (verified.isInstalled()) {
                    if (callback != null) callback.onProgress(100.0, 
                        "Blender " + verified.getVersion() + " installed successfully!");
                    return true;
                }
            }
            System.err.println("[INSTALLER-ERR] Binary exists but version check failed.");
            return false;
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Linux standalone installation error: " + e.getMessage());
        }
        return false;
    }

    public static File determineWindowsInstallDir() {
        // 1. Custom override from property or environment variable
        String custom = System.getProperty("blender.install.dir");
        if (custom == null || custom.trim().isEmpty()) {
            custom = System.getenv("BLENDER_INSTALL_DIR");
        }
        if (custom != null && !custom.trim().isEmpty()) {
            File cDir = new File(custom.trim());
            if (testWritableDir(cDir)) return cDir;
        }

        // 2. Primary target: "C:\Program Files\Blender Foundation\Blender 5.1"
        File progFiles = new File("C:\\Program Files\\Blender Foundation\\Blender 5.1");
        if (testWritableDir(progFiles)) {
            return progFiles;
        }

        // 3. Fallback on C: drive: "C:\Blender\Blender 5.1"
        File cBlender = new File("C:\\Blender\\Blender 5.1");
        if (testWritableDir(cBlender)) {
            return cBlender;
        }

        // 4. Fallback in user LocalAppData
        String localApp = System.getenv("LOCALAPPDATA");
        if (localApp != null && !localApp.trim().isEmpty()) {
            File userProg = new File(localApp, "Programs\\Blender Foundation\\Blender 5.1");
            if (testWritableDir(userProg)) {
                return userProg;
            }
        }

        // 5. Working directory fallback
        File localBin = new File("./blender_bin").getAbsoluteFile();
        localBin.mkdirs();
        return localBin;
    }

    private static boolean testWritableDir(File dir) {
        try {
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    return false;
                }
            }
            File testProbe = new File(dir, ".cg_perm_test_" + System.currentTimeMillis() + ".tmp");
            if (testProbe.createNewFile()) {
                testProbe.delete();
                return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean installOnWindows(ProgressCallback callback) {
        return installOnWindows("", callback);
    }

    private static boolean installOnWindows(String downloadUrl, ProgressCallback callback) {
        File destDir = determineWindowsInstallDir();
        System.out.println("[INSTALLER] Target installation directory: " + destDir.getAbsolutePath());
        if (callback != null) {
            callback.onProgress(10.0, "Target installation path: " + destDir.getAbsolutePath());
        }

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "CampusGrid");
        if (!tempDir.exists()) tempDir.mkdirs();
        File zipFile = new File(tempDir, "blender-5.1.2-windows-x64.zip");

        String officialUrl = "https://download.blender.org/release/Blender5.1/blender-5.1.2-windows-x64.zip";
        String primaryUrl = (downloadUrl != null && !downloadUrl.trim().isEmpty()) ? downloadUrl.trim() : officialUrl;

        boolean downloaded = false;

        // Check if cached zip already exists and is complete (>300MB)
        if (zipFile.exists() && zipFile.length() > 300_000_000L) {
            System.out.println("[INSTALLER] Valid Blender archive already cached at: " + zipFile.getAbsolutePath());
            if (callback != null) callback.onProgress(70.0, "Using cached Blender archive (" + (zipFile.length() / 1_048_576) + " MB)...");
            downloaded = true;
        } else {
            // Attempt download from primaryUrl (e.g. Master node or specified URL)
            if (callback != null) callback.onProgress(15.0, "Downloading Blender 5.1 standalone package...");
            System.out.println("[INSTALLER] Downloading Blender 5.1 from: " + primaryUrl);
            downloaded = downloadFileWithProgress(primaryUrl, zipFile, callback, 15.0, 75.0);

            // If primaryUrl was Master's URL and failed, fallback to official CDN directly
            if (!downloaded && !primaryUrl.equals(officialUrl)) {
                System.out.println("[INSTALLER] Primary download source unavailable. Falling back directly to official Blender CDN: " + officialUrl);
                if (callback != null) callback.onProgress(15.0, "Connecting directly to official Blender CDN...");
                downloaded = downloadFileWithProgress(officialUrl, zipFile, callback, 15.0, 75.0);
            }
        }

        if (!downloaded || !zipFile.exists() || zipFile.length() < 50_000_000L) {
            System.err.println("[INSTALLER-ERR] Failed downloading Blender archive package.");
            if (callback != null) callback.onProgress(-1.0, "Failed downloading Blender archive package.");
            return false;
        }

        // Extraction
        if (callback != null) callback.onProgress(78.0, "Extracting Blender 5.1 to " + destDir.getAbsolutePath() + "...");
        System.out.println("[INSTALLER] Extracting archive to: " + destDir.getAbsolutePath());

        boolean extracted = false;
        // Fast path: try tar.exe (Windows 10/11 built-in bsdtar with --strip-components=1)
        String whichTar = BlenderUtils.executeCommandSilently("where", "tar");
        if (whichTar != null && !whichTar.trim().isEmpty()) {
            System.out.println("[INSTALLER] Using native tar for fast extraction...");
            BlenderUtils.executeCommandWithTimeout(300, "tar", "-xf", zipFile.getAbsolutePath(), "-C", destDir.getAbsolutePath(), "--strip-components=1");
            File testExe = new File(destDir, "blender.exe");
            if (testExe.exists()) {
                extracted = true;
                System.out.println("[INSTALLER] Native extraction succeeded: " + testExe.getAbsolutePath());
            }
        }

        if (!extracted) {
            System.out.println("[INSTALLER] Running Java extraction...");
            try {
                extractZip(zipFile, destDir, callback, 78.0, 94.0);
            } catch (Exception e) {
                System.err.println("[INSTALLER-ERR] Java zip extraction failed: " + e.getMessage());
            }
        }

        // Check if blender.exe exists directly or inside a nested subfolder
        File blenderExe = new File(destDir, "blender.exe");
        if (!blenderExe.exists()) {
            File[] subdirs = destDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File sub : subdirs) {
                    File subExe = new File(sub, "blender.exe");
                    if (subExe.exists()) {
                        blenderExe = subExe;
                        break;
                    }
                }
            }
        }

        if (blenderExe.exists()) {
            System.out.println("[INSTALLER] Binary found at: " + blenderExe.getAbsolutePath());
        }

        // Verify installation
        if (callback != null) callback.onProgress(96.0, "Verifying Blender installation...");
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
            Status verified = getInstallationStatus();
            if (verified.isInstalled()) {
                if (callback != null) callback.onProgress(100.0, 
                    "Blender " + verified.getVersion() + " installed and ready at " + verified.getExecutablePath() + "!");
                System.out.println("[INSTALLER] ✔ Blender installed and verified: " + verified.getExecutablePath() + " (v" + verified.getVersion() + ")");
                return true;
            }
        }

        System.err.println("[INSTALLER-ERR] Binary extraction finished but version verification check failed.");
        return false;
    }

    /**
     * Pure Java HTTP file downloader with progress callback.
     * Requires 0 external package managers or CLI tools.
     */
    public static boolean downloadFileWithProgress(String urlStr, File destFile, ProgressCallback callback, double startPct, double endPct) {
        try {
            int maxRedirects = 10;
            String currentUrl = urlStr;

            for (int i = 0; i < maxRedirects; i++) {
                URL url = URI.create(currentUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false); // handle manually
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) CampusGrid-BlenderInstaller/1.0");
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(120_000); // 2 min per chunk, not total
                conn.connect();

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    currentUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (currentUrl == null) return false;
                    continue;
                }
                if (code != 200) {
                    System.err.println("[INSTALLER-ERR] HTTP " + code + " for " + currentUrl);
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
                        if (now - lastReport > 500 && totalBytes > 0) {
                            lastReport = now;
                            double fraction = (double) downloadedBytes / totalBytes;
                            double pct = startPct + fraction * (endPct - startPct);
                            if (callback != null) {
                                callback.onProgress(pct, String.format("Downloading Blender: %.1f / %.1f MB",
                                    downloadedBytes / 1_048_576.0, totalBytes / 1_048_576.0));
                            }
                        }
                    }
                }
                conn.disconnect();
                return destFile.exists() && destFile.length() > 10_000_000; // sanity: at least 10MB
            }
            return false;
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Download failed: " + e.getMessage());
            return false;
        }
    }

    private static void extractZip(File zipFile, File destDir, ProgressCallback callback, double startPct, double endPct) throws Exception {
        try (java.util.zip.ZipInputStream zis = 
                new java.util.zip.ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zipFile), 131072))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[65536];
            int count = 0;
            long lastReport = System.currentTimeMillis();
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                // Strip the top-level folder (e.g. "blender-5.1.2-windows-x64/")
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                if (name.isEmpty()) { zis.closeEntry(); continue; }

                File newFile = new File(destDir, name);
                // Zip slip protection
                if (!newFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new SecurityException("Zip slip detected: " + entry.getName());
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
                    double currentPct = Math.min(endPct, startPct + (count % 1000) * 0.015);
                    if (callback != null) {
                        callback.onProgress(currentPct, "Extracting files (" + count + " items extracted)...");
                    }
                }
            }
        }
    }
}
