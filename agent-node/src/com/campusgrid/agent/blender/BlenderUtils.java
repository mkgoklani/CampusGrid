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

    /**
     * Finds the absolute path to the Blender executable on the host system.
     * Supports Ubuntu/Linux (via 'which' and common paths) and Windows (via 'where' and Registry/Program Files folders).
     *
     * @return the absolute path to the Blender executable, or null if it cannot be found.
     */
    public static String findExecutablePath() {
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            // 1. Try 'where' command on Windows
            String pathFromWhere = executeCommandSilently("where", "blender");
            if (pathFromWhere != null && !pathFromWhere.isEmpty()) {
                String firstPath = pathFromWhere.split("\r?\n")[0].trim();
                if (new File(firstPath).canExecute()) {
                    return firstPath;
                }
            }
            
            // 2. Try common default installation paths on Windows
            String[] commonWinPaths = {
                "C:\\Program Files\\Blender Foundation\\Blender 4.2\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 4.1\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 4.0\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.6\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.5\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.4\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.3\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.2\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 3.0\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 2.93\\blender.exe",
                "C:\\Program Files\\Blender Foundation\\Blender 2.83\\blender.exe"
            };
            for (String p : commonWinPaths) {
                File f = new File(p);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
            
            // 3. Scan the parent Blender Foundation directory
            File blenderFoundationDir = new File("C:\\Program Files\\Blender Foundation");
            if (blenderFoundationDir.exists() && blenderFoundationDir.isDirectory()) {
                File[] subdirs = blenderFoundationDir.listFiles();
                if (subdirs != null) {
                    for (File subdir : subdirs) {
                        if (subdir.isDirectory()) {
                            File exe = new File(subdir, "blender.exe");
                            if (exe.exists() && exe.canExecute()) {
                                return exe.getAbsolutePath();
                            }
                        }
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
            String workDir = System.getProperty("user.dir");
            String[] commonUnixPaths = {
                "/Applications/Blender.app/Contents/MacOS/Blender",
                "/Applications/Blender.app/Contents/MacOS/blender",
                userHome + "/Applications/Blender.app/Contents/MacOS/Blender",
                workDir + "/blender_bin/Blender.app/Contents/MacOS/Blender",
                workDir + "/blender_bin/blender",
                "./blender_bin/blender",
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
            
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
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
