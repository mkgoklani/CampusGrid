package com.campusgrid.agent.blender;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for locating Blender and executing shell commands safely.
 */
public class BlenderUtils {

    private static final Pattern VERSION_PATTERN = Pattern.compile("Blender\\s+(\\d+\\.\\d+(?:\\.\\d+)?)");

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") || 
               System.getProperty("os.name", "").toLowerCase().contains("darwin");
    }

    public static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("unix");
    }

    public static String getOsType() {
        if (isWindows()) return "windows";
        if (isMac()) return "macos";
        return "linux";
    }

    public static String getSystemArch() {
        String raw = System.getProperty("os.arch", "x64").toLowerCase();
        if (raw.contains("aarch64") || raw.contains("arm64")) return "arm64";
        if (raw.contains("86") && !raw.contains("64")) return "x86";
        return "x64";
    }

    /**
     * Finds the absolute path to the Blender executable on the host system.
     * Supports Ubuntu/Linux (via 'which' and common paths), macOS (/Applications, Homebrew), and Windows (Program Files, where).
     *
     * @return the absolute path to the Blender executable, or null if it cannot be found.
     */
    public static String findExecutablePath() {
        if (isWindows()) {
            // 1. Try 'where' command on Windows for blender.exe and blender
            String pathFromWhere = executeCommandSilently("where", "blender.exe");
            if (pathFromWhere == null || pathFromWhere.isEmpty()) {
                pathFromWhere = executeCommandSilently("where", "blender");
            }
            if (pathFromWhere != null && !pathFromWhere.isEmpty()) {
                for (String p : pathFromWhere.split("\r?\n")) {
                    File f = new File(p.trim());
                    if (f.exists() && f.isFile()) {
                        return f.getAbsolutePath();
                    }
                }
            }
            
            String workDir;
            try {
                workDir = new File(".").getCanonicalPath();
            } catch (Exception e) {
                workDir = System.getProperty("user.dir");
            }
            
            String userHome = System.getProperty("user.home", "C:\\Users\\Default");
            String localAppData = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles == null) programFiles = "C:\\Program Files";
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            if (programFilesX86 == null) programFilesX86 = "C:\\Program Files (x86)";

            // 2. Direct paths
            String[] commonWinPaths = {
                workDir + "\\blender_bin\\blender.exe",
                workDir + "\\..\\blender_bin\\blender.exe",
                userHome + "\\blender_bin\\blender.exe",
                "C:\\blender_bin\\blender.exe",
                "C:\\Blender\\blender.exe",
                "D:\\Blender\\blender.exe",
                "E:\\Blender\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 5.2\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 5.1\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 5.0\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 4.4\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 4.3\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 4.2\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 4.1\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 4.0\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.6\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.5\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.4\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.3\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.2\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.1\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 3.0\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 2.93\\blender.exe",
                programFiles + "\\Blender Foundation\\Blender 2.83\\blender.exe",
                programFilesX86 + "\\Blender Foundation\\Blender 4.2\\blender.exe",
                programFilesX86 + "\\Blender Foundation\\Blender 3.6\\blender.exe",
                programFiles + "\\Steam\\steamapps\\common\\Blender\\blender.exe",
                programFilesX86 + "\\Steam\\steamapps\\common\\Blender\\blender.exe",
                "D:\\Steam\\steamapps\\common\\Blender\\blender.exe",
                "D:\\SteamLibrary\\steamapps\\common\\Blender\\blender.exe",
                "E:\\Steam\\steamapps\\common\\Blender\\blender.exe",
                "E:\\SteamLibrary\\steamapps\\common\\Blender\\blender.exe",
                localAppData != null ? localAppData + "\\Programs\\Blender Foundation\\blender.exe" : null,
                localAppData != null ? localAppData + "\\Programs\\Blender\\blender.exe" : null
            };
            for (String p : commonWinPaths) {
                if (p == null) continue;
                File f = new File(p);
                if (f.exists() && f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
            
            // 3. Recursive directory scans for parent folders on Windows
            String[] searchDirs = {
                programFiles + "\\Blender Foundation",
                programFilesX86 + "\\Blender Foundation",
                programFiles + "\\Blender",
                programFilesX86 + "\\Blender",
                "C:\\Blender",
                "D:\\Blender",
                workDir + "\\blender_bin",
                workDir + "\\..\\blender_bin",
                userHome + "\\blender_bin",
                localAppData != null ? localAppData + "\\Programs\\Blender Foundation" : null,
                localAppData != null ? localAppData + "\\Microsoft\\WinGet\\Packages" : null
            };
            for (String sDir : searchDirs) {
                if (sDir == null) continue;
                File d = new File(sDir);
                if (d.exists() && d.isDirectory()) {
                    File found = findExecutableInDir(d, "blender.exe");
                    if (found == null) found = findExecutableInDir(d, "blender");
                    if (found != null && found.isFile()) {
                        return found.getAbsolutePath();
                    }
                }
            }
        } else {
            // Linux/Ubuntu / macOS
            // 1. Try 'which' command
            String pathFromWhich = executeCommandSilently("which", "blender");
            if (pathFromWhich != null && !pathFromWhich.isEmpty()) {
                String trimmedPath = pathFromWhich.trim();
                if (new File(trimmedPath).canExecute()) {
                    return trimmedPath;
                }
            }
            
            // 2. Try common installation paths on macOS & Linux
            String userHome = System.getProperty("user.home");
            String workDir;
            try {
                workDir = new File(".").getCanonicalPath();
            } catch (Exception e) {
                workDir = System.getProperty("user.dir");
            }
            String[] commonUnixPaths = {
                "/Applications/Blender.app/Contents/MacOS/Blender",
                "/Applications/Blender.app/Contents/MacOS/blender",
                userHome + "/Applications/Blender.app/Contents/MacOS/Blender",
                userHome + "/Applications/Blender.app/Contents/MacOS/blender",
                workDir + "/blender_bin/Blender.app/Contents/MacOS/Blender",
                workDir + "/blender_bin/Blender.app/Contents/MacOS/blender",
                workDir + "/blender_bin/blender",
                workDir + "/blender_bin/blender.exe",
                workDir + "/../blender_bin/blender",
                workDir + "/../blender_bin/Blender.app/Contents/MacOS/Blender",
                "/opt/homebrew/bin/blender",
                "/usr/local/bin/blender",
                "/usr/bin/blender",
                "/snap/bin/blender"
            };
            for (String p : commonUnixPaths) {
                File f = new File(p);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }

            // 3. Scan local ./blender_bin and ../blender_bin recursively for portable blender binaries
            String[] binDirs = { workDir + "/blender_bin", workDir + "/../blender_bin" };
            for (String bDir : binDirs) {
                File b = new File(bDir);
                if (b.exists() && b.isDirectory()) {
                    File found = findExecutableInDir(b, "blender");
                    if (found != null) return found.getAbsolutePath();
                }
            }
        }
        
        return null;
    }

    private static File findExecutableInDir(File dir, String baseName) {
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(baseName)) {
                return f;
            } else if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("node_modules")) {
                File sub = findExecutableInDir(f, baseName);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    /**
     * Executes a system shell command safely using ProcessBuilder and returns the captured output.
     * Arguments are passed as an array to prevent shell injection vulnerabilities.
     *
     * @param command the command and its arguments.
     * @return the command stdout and stderr, or null if execution fails.
     */
    public static String executeCommand(String... command) {
        return executeCommandWithTimeout(10, command);
    }

    /**
     * Executes a system shell command safely using ProcessBuilder and returns the captured output,
     * waiting up to the specified timeout.
     */
    public static String executeCommandWithTimeout(int timeoutSeconds, String... command) {
        if (command == null || command.length == 0) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return null;
            }
            
            return output.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Executes a system command silently without throwing errors or printing diagnostic warnings.
     *
     * @param command the command and its arguments.
     * @return the captured output string, or null if it fails.
     */
    private static String executeCommandSilently(String... command) {
        return executeCommand(command);
    }

    /**
     * Parses the Blender version string from a given command output string.
     * Looks for the standard "Blender X.Y.Z" format.
     *
     * @param versionOutput the stdout output containing the Blender version info.
     * @return the parsed version string (e.g. "3.6.2"), or "Unknown" if not found.
     */
    public static String parseVersion(String versionOutput) {
        if (versionOutput == null || versionOutput.isEmpty()) {
            return "Unknown";
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "Unknown";
    }
}
