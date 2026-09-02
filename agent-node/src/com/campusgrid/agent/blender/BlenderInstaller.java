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
        // Stage 1: Try Master Node local download URL if provided
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            System.out.println("[INSTALLER] Attempting Stage 1: Master Node direct DMG download from: " + downloadUrl);
            if (callback != null) callback.onProgress(10.0, "Downloading Blender DMG from Master Node...");
            if (directDownloadMacDMG(downloadUrl, callback)) {
                return true;
            }
            System.out.println("[INSTALLER-WARN] Master Node DMG download failed. Falling back to official CDN...");
        }

        // Stage 2: Fallback to Official Blender Foundation CDN
        System.out.println("[INSTALLER] Attempting Stage 2: Official CDN DMG download...");
        if (callback != null) callback.onProgress(15.0, "Downloading from official Blender CDN...");
        if (directDownloadMacDMG("", callback)) {
            return true;
        }
        System.out.println("[INSTALLER-WARN] Direct CDN download failed. Falling back to Homebrew...");

        // Stage 3: Homebrew Package Manager
        System.out.println("[INSTALLER] Attempting Stage 3: Homebrew package installation...");
        String brewPath = findBrewPath();
        if (brewPath == null) {
            if (callback != null) callback.onProgress(20.0, "Homebrew not found. Bootstrapping package manager...");
            try {
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", 
                    "NONINTERACTIVE=1 /bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                proc.waitFor();
                brewPath = findBrewPath();
            } catch (Exception ignored) {}
        }

        if (brewPath != null) {
            try {
                if (callback != null) callback.onProgress(30.0, "Running Homebrew to install Blender 3D suite...");
                ProcessBuilder pb = new ProcessBuilder(brewPath, "install", "--cask", "blender");
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    double currentPct = 35.0;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[INSTALLER-LOG] " + line);
                        if (line.contains("Downloading") || line.contains("Fetching")) {
                            currentPct = Math.min(75.0, currentPct + 6.0);
                            if (callback != null) callback.onProgress(currentPct, "Downloading Blender package via Homebrew...");
                        } else if (line.contains("Installing") || line.contains("Moving") || line.contains("Linking")) {
                            currentPct = Math.min(90.0, currentPct + 8.0);
                            if (callback != null) callback.onProgress(currentPct, "Installing Blender to /Applications...");
                        }
                    }
                }

                proc.waitFor();
                if (callback != null) callback.onProgress(95.0, "Verifying installed Blender binary...");
                Thread.sleep(1000);

                Status verified = getInstallationStatus();
                if (verified.isInstalled()) {
                    if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                    System.out.println("[INSTALLER] ✔ Blender installed via Homebrew: " + verified.getExecutablePath());
                    return true;
                }
            } catch (Exception e) {
                System.err.println("[INSTALLER-ERR] Homebrew installation exception: " + e.getMessage());
            }
        }

        return false;
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
            String url = (downloadUrl != null && !downloadUrl.trim().isEmpty())
                ? (downloadUrl.contains("?") ? downloadUrl + "&arch=" + (isArm ? "arm64" : "x64") : downloadUrl + "?arch=" + (isArm ? "arm64" : "x64"))
                : (isArm 
                    ? "https://download.blender.org/release/Blender4.2/blender-4.2.0-macos-arm64.dmg"
                    : "https://download.blender.org/release/Blender4.2/blender-4.2.0-macos-x64.dmg");

            boolean downloaded = false;
            String whichCurl = BlenderUtils.executeCommand("which", "curl");
            if (whichCurl != null && !whichCurl.trim().isEmpty()) {
                if (callback != null) callback.onProgress(20.0, "Downloading Blender 4.2 official DMG (~300MB)...");
                System.out.println("[INSTALLER] Downloading Blender DMG via curl: " + url);
                BlenderUtils.executeCommandWithTimeout(300, whichCurl.trim(), "-L", "-f", "-o", dmgFile.getAbsolutePath(), url);
                downloaded = dmgFile.exists() && dmgFile.length() > 20_000_000;
            }

            if (!downloaded) {
                if (callback != null) callback.onProgress(20.0, "Downloading Blender DMG via Java HTTP streaming...");
                downloaded = downloadFileWithProgress(url, dmgFile, callback, 20.0, 75.0);
            }

            if (!downloaded) {
                System.err.println("[INSTALLER-ERR] Failed downloading Blender DMG from: " + url);
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
                
                String targetApp = "/Applications/Blender.app";
                BlenderUtils.executeCommand("cp", "-R", mountedApp.getAbsolutePath(), "/Applications/");
                
                File targetFile = new File(targetApp);
                if (!targetFile.exists()) {
                    String userApp = System.getProperty("user.home") + "/Applications/Blender.app";
                    File userAppDir = new File(System.getProperty("user.home") + "/Applications");
                    if (!userAppDir.exists()) userAppDir.mkdirs();
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
        File binDir = new File("./blender_bin");
        if (!binDir.exists()) binDir.mkdirs();
        File tarFile = new File("./downloads/blender-linux.tar.xz");
        if (!tarFile.getParentFile().exists()) tarFile.getParentFile().mkdirs();

        boolean downloaded = false;

        // Stage 1: Try Master Node direct URL
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            System.out.println("[INSTALLER] Linux Stage 1: Downloading from Master Node: " + downloadUrl);
            if (callback != null) callback.onProgress(10.0, "Downloading Blender archive from Master Node...");
            downloaded = downloadFileWithProgress(downloadUrl, tarFile, callback, 10.0, 65.0);
            if (!downloaded) {
                System.out.println("[INSTALLER-WARN] Master Node download failed. Falling back to official CDN...");
            }
        }

        // Stage 2: Fallback to official Blender CDN
        if (!downloaded) {
            String cdnUrl = "https://download.blender.org/release/Blender4.2/blender-4.2.0-linux-x64.tar.xz";
            System.out.println("[INSTALLER] Linux Stage 2: Downloading from Blender official CDN: " + cdnUrl);
            if (callback != null) callback.onProgress(15.0, "Downloading standalone Blender archive from CDN...");
            downloaded = downloadFileWithProgress(cdnUrl, tarFile, callback, 15.0, 65.0);
        }

        // Extraction
        if (downloaded && tarFile.exists() && tarFile.length() > 10_000_000) {
            try {
                if (callback != null) callback.onProgress(70.0, "Extracting portable Blender (may take several minutes)...");
                BlenderUtils.executeCommandWithTimeout(300, "tar", "-xf", tarFile.getAbsolutePath(), "-C", binDir.getAbsolutePath(), "--strip-components=1");
                
                File blenderExe = new File(binDir, "blender");
                if (blenderExe.exists()) {
                    blenderExe.setExecutable(true);
                    System.out.println("[INSTALLER] Binary found at: " + blenderExe.getAbsolutePath());
                }

                for (int attempt = 0; attempt < 3; attempt++) {
                    Thread.sleep(1000);
                    Status verified = getInstallationStatus();
                    if (verified.isInstalled()) {
                        if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                        return true;
                    }
                }
            } catch (Exception e) {
                System.err.println("[INSTALLER-ERR] Archive extraction failed: " + e.getMessage());
            }
        }

        // Stage 3: Fallback to snap or apt
        System.out.println("[INSTALLER] Linux Stage 3: Attempting package manager (snap/apt)...");
        if (callback != null) callback.onProgress(75.0, "Attempting Snap package installation...");
        try {
            BlenderUtils.executeCommandWithTimeout(180, "sudo", "snap", "install", "blender", "--classic");
            Status verified = getInstallationStatus();
            if (verified.isInstalled()) {
                if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed via Snap!");
                return true;
            }
        } catch (Exception ignored) {}

        try {
            if (callback != null) callback.onProgress(85.0, "Attempting APT package installation...");
            BlenderUtils.executeCommandWithTimeout(180, "sudo", "apt-get", "update", "-y");
            BlenderUtils.executeCommandWithTimeout(180, "sudo", "apt-get", "install", "-y", "blender");
            Status verified = getInstallationStatus();
            if (verified.isInstalled()) {
                if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed via APT!");
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    private static boolean installOnWindows(ProgressCallback callback) {
        return installOnWindows("", callback);
    }

    private static boolean installOnWindows(String downloadUrl, ProgressCallback callback) {
        File binDir = new File("./blender_bin");
        if (!binDir.exists()) binDir.mkdirs();
        File zipFile = new File("./downloads/blender-win.zip");
        if (!zipFile.getParentFile().exists()) zipFile.getParentFile().mkdirs();

        boolean downloaded = false;

        // Stage 1: Try Master Node direct URL
        if (downloadUrl != null && !downloadUrl.trim().isEmpty()) {
            System.out.println("[INSTALLER] Windows Stage 1: Downloading from Master Node: " + downloadUrl);
            if (callback != null) callback.onProgress(10.0, "Downloading Blender ZIP from Master Node...");
            downloaded = downloadFileWithProgress(downloadUrl, zipFile, callback, 10.0, 75.0);
            if (!downloaded) {
                System.out.println("[INSTALLER-WARN] Master Node download failed. Falling back to official CDN...");
            }
        }

        // Stage 2: Fallback to official Blender CDN
        if (!downloaded) {
            String cdnUrl = "https://download.blender.org/release/Blender4.2/blender-4.2.0-windows-x64.zip";
            System.out.println("[INSTALLER] Windows Stage 2: Downloading from Blender official CDN: " + cdnUrl);
            if (callback != null) callback.onProgress(15.0, "Downloading portable Blender ZIP from CDN...");
            downloaded = downloadFileWithProgress(cdnUrl, zipFile, callback, 15.0, 75.0);
        }

        if (downloaded && zipFile.exists() && zipFile.length() > 10_000_000) {
            try {
                if (callback != null) callback.onProgress(80.0, "Extracting portable Blender...");
                extractZip(zipFile, binDir);

                String foundExe = BlenderUtils.findExecutablePath();
                File blenderExe = foundExe != null ? new File(foundExe) : new File(binDir, "blender.exe");
                if (blenderExe.exists()) {
                    blenderExe.setExecutable(true);
                    System.out.println("[INSTALLER] Binary found at: " + blenderExe.getAbsolutePath());
                }

                for (int attempt = 0; attempt < 3; attempt++) {
                    Thread.sleep(1000);
                    Status verified = getInstallationStatus();
                    if (verified.isInstalled()) {
                        if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                        return true;
                    }
                }
            } catch (Exception e) {
                System.err.println("[INSTALLER-ERR] Windows ZIP extraction error: " + e.getMessage());
            }
        }

        // Stage 3: Fallback to winget
        System.out.println("[INSTALLER] Windows Stage 3: Checking winget package manager...");
        try {
            String whichWinget = BlenderUtils.executeCommand("where", "winget");
            if (whichWinget != null && !whichWinget.isEmpty()) {
                if (callback != null) callback.onProgress(85.0, "Invoking Windows Package Manager (winget)...");
                BlenderUtils.executeCommand("winget", "install", "--id", "BlenderFoundation.Blender", "-e", "--silent");
                Thread.sleep(2000);
                Status verified = getInstallationStatus();
                if (verified.isInstalled()) {
                    if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed via winget!");
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Pure Java HTTP file downloader with progress callback.
     * Supports follow-redirects (including relative URLs) and timeouts.
     */
    public static boolean downloadFileWithProgress(String urlStr, File destFile, ProgressCallback callback, double startPct, double endPct) {
        if (urlStr == null || urlStr.trim().isEmpty()) return false;
        try {
            int maxRedirects = 10;
            String currentUrl = urlStr.trim();

            for (int i = 0; i < maxRedirects; i++) {
                URI uri = URI.create(currentUrl);
                URL url = uri.toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false); // handle manually to support cross-protocol/relative redirects
                conn.setRequestProperty("User-Agent", "CampusGrid-BlenderInstaller/1.0");
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(120_000);
                conn.connect();

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) return false;
                    currentUrl = uri.resolve(loc).toString();
                    continue;
                }
                if (code != 200) {
                    System.err.println("[INSTALLER-ERR] HTTP " + code + " for " + currentUrl);
                    conn.disconnect();
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
                        if (now - lastReport > 400 && totalBytes > 0) {
                            lastReport = now;
                            double fraction = (double) downloadedBytes / totalBytes;
                            double pct = startPct + fraction * (endPct - startPct);
                            if (callback != null) {
                                callback.onProgress(pct, String.format("Downloading: %.1f / %.1f MB",
                                    downloadedBytes / 1_048_576.0, totalBytes / 1_048_576.0));
                            }
                        }
                    }
                }
                conn.disconnect();
                return destFile.exists() && destFile.length() > 5_000_000;
            }
            return false;
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Download failed: " + e.getMessage());
            if (destFile.exists()) destFile.delete();
            return false;
        }
    }

    private static void extractZip(File zipFile, File destDir) throws Exception {
        try (java.util.zip.ZipInputStream zis = 
                new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                // If the entry contains a top-level directory folder, strip only the first folder component
                int slash = name.indexOf('/');
                if (slash >= 0 && name.contains("/")) {
                    name = name.substring(slash + 1);
                }
                if (name.isEmpty()) { zis.closeEntry(); continue; }

                File newFile = new File(destDir, name);
                // Zip slip protection
                if (!newFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
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
            }
        }
    }
}
