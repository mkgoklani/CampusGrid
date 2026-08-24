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

        ProgressCallback safeCallback = (pct, msg) -> {
            currentInstallProgress = pct;
            if (callback != null) callback.onProgress(pct, msg);
        };

        safeCallback.onProgress(5.0, "Initiating Blender automated installation pipeline...");

        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("mac")) {
                return installOnMac(safeCallback);
            } else if (os.contains("win")) {
                return installOnWindows(safeCallback);
            } else {
                return installOnLinux(safeCallback);
            }
        } finally {
            isInstalling = false;
            currentInstallProgress = -1.0;
        }
    }

    private static boolean installOnMac(ProgressCallback callback) {
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
        try {
            File tempDir = new File("./downloads");
            if (!tempDir.exists()) tempDir.mkdirs();
            File dmgFile = new File(tempDir, "blender_macos.dmg");

            boolean isArm = System.getProperty("os.arch").toLowerCase().contains("aarch64") 
                         || System.getProperty("os.arch").toLowerCase().contains("arm");
            String url = isArm 
                ? "https://download.blender.org/release/Blender4.2/blender-4.2.0-macos-arm64.dmg"
                : "https://download.blender.org/release/Blender4.2/blender-4.2.0-macos-x64.dmg";

            String whichCurl = BlenderUtils.executeCommand("which", "curl");
            boolean downloaded = false;
            if (whichCurl != null && !whichCurl.isEmpty()) {
                if (callback != null) callback.onProgress(15.0, "Downloading Blender 4.2 official DMG (~300MB)...");
                System.out.println("[INSTALLER] Downloading Blender DMG via curl: " + url);
                BlenderUtils.executeCommand(whichCurl.trim(), "-L", "-o", dmgFile.getAbsolutePath(), url);
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
            BlenderUtils.executeCommand("hdiutil", "attach", dmgFile.getAbsolutePath(), "-nobrowse", "-readonly");

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
        System.out.println("[INSTALLER] Linux detected. Checking apt package manager...");
        try {
            String whichApt = BlenderUtils.executeCommand("which", "apt-get");
            if (whichApt != null && !whichApt.isEmpty()) {
                if (callback != null) callback.onProgress(15.0, "Updating apt package list...");
                BlenderUtils.executeCommand("sudo", "apt-get", "update", "-y");
                if (callback != null) callback.onProgress(40.0, "Installing Blender via apt...");
                BlenderUtils.executeCommand("sudo", "apt-get", "install", "-y", "blender");

                Status verified = getInstallationStatus();
                if (verified.isInstalled()) {
                    if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: Direct Official Portable tar.xz download
        System.out.println("[INSTALLER] Running direct standalone Linux tar.xz download...");
        try {
            File binDir = new File("./blender_bin");
            if (!binDir.exists()) binDir.mkdirs();
            File tarFile = new File("./downloads/blender-linux.tar.xz");
            if (!tarFile.getParentFile().exists()) tarFile.getParentFile().mkdirs();

            String url = "https://download.blender.org/release/Blender4.2/blender-4.2.0-linux-x64.tar.xz";
            if (callback != null) callback.onProgress(20.0, "Downloading standalone Blender archive...");
            downloadFileWithProgress(url, tarFile, callback, 20.0, 75.0);

            if (callback != null) callback.onProgress(80.0, "Extracting portable Blender...");
            BlenderUtils.executeCommand("tar", "-xf", tarFile.getAbsolutePath(), "-C", binDir.getAbsolutePath(), "--strip-components=1");
            new File(binDir, "blender").setExecutable(true);

            Status verified = getInstallationStatus();
            return verified.isInstalled();
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Linux standalone installation error: " + e.getMessage());
        }
        return false;
    }

    private static boolean installOnWindows(ProgressCallback callback) {
        System.out.println("[INSTALLER] Windows detected. Running standalone portable Blender 4.2 pipeline...");
        try {
            File binDir = new File("./blender_bin");
            if (!binDir.exists()) binDir.mkdirs();
            File tempDir = new File("./downloads");
            if (!tempDir.exists()) tempDir.mkdirs();
            File zipFile = new File(tempDir, "blender-windows-x64.zip");

            String url = "https://download.blender.org/release/Blender4.2/blender-4.2.0-windows-x64.zip";
            boolean downloaded = false;

            if (zipFile.exists() && zipFile.length() > 100000000) {
                System.out.println("[INSTALLER] Cached ZIP found: " + zipFile.getAbsolutePath() + " (" + (zipFile.length() / (1024 * 1024)) + " MB)");
                downloaded = true;
                if (callback != null) callback.onProgress(75.0, "Found cached Blender archive. Ready for extraction...");
            } else {
                if (callback != null) callback.onProgress(15.0, "Connecting to Blender CDN...");
                downloaded = downloadFileWithProgress(url, zipFile, callback, 15.0, 75.0);
            }

            if (!downloaded || !zipFile.exists() || zipFile.length() < 10000000) {
                System.err.println("[INSTALLER-ERR] Failed downloading portable Blender ZIP from " + url);
                return false;
            }

            if (callback != null) callback.onProgress(80.0, "Extracting portable Blender suite into ./blender_bin...");
            System.out.println("[INSTALLER] Extracting " + zipFile.getAbsolutePath() + " to " + binDir.getAbsolutePath());
            extractZip(zipFile, binDir);

            if (callback != null) callback.onProgress(95.0, "Verifying extracted Blender binary...");
            Thread.sleep(1000);
            Status verified = getInstallationStatus();
            if (verified.isInstalled()) {
                if (callback != null) callback.onProgress(100.0, "Blender " + verified.getVersion() + " installed successfully!");
                System.out.println("[INSTALLER] ✔ Blender portable installed successfully: " + verified.getExecutablePath());
                return true;
            } else {
                System.err.println("[INSTALLER-ERR] Extraction completed but Blender executable was not located.");
            }
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Windows standalone installation error: " + e.getMessage());
        }
        return false;
    }

    /**
     * Pure Java HTTP file downloader with progress callback.
     * Requires 0 external package managers or CLI tools.
     */
    public static boolean downloadFileWithProgress(String urlStr, File destFile, ProgressCallback callback, double startPct, double endPct) {
        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "CampusGrid-BlenderInstaller/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String redirect = conn.getHeaderField("Location");
                if (redirect != null) {
                    return downloadFileWithProgress(redirect, destFile, callback, startPct, endPct);
                }
            }

            long totalBytes = conn.getContentLengthLong();
            long downloadedBytes = 0;

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(destFile)) {

                byte[] buffer = new byte[65536];
                int read;
                long lastReport = System.currentTimeMillis();

                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloadedBytes += read;

                    long now = System.currentTimeMillis();
                    if (now - lastReport > 400 && totalBytes > 0) {
                        lastReport = now;
                        double fraction = (double) downloadedBytes / totalBytes;
                        double currentPct = startPct + fraction * (endPct - startPct);
                        if (callback != null) {
                            String msg = String.format("Downloading Blender: %.1f MB / %.1f MB", 
                                downloadedBytes / (1024.0 * 1024.0), totalBytes / (1024.0 * 1024.0));
                            callback.onProgress(currentPct, msg);
                        }
                    }
                }
            }
            return destFile.exists() && destFile.length() > 0;
        } catch (Exception e) {
            System.err.println("[INSTALLER-ERR] Download failed from " + urlStr + ": " + e.getMessage());
            return false;
        }
    }

    private static void extractZip(File zipFile, File destDir) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
