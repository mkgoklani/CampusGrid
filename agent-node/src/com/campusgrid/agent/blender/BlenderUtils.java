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
     * Supports Ubuntu/Linux, macOS, and Windows with system PATH, default install locations,
     * and local portable ./blender_bin distributions.
     *
     * @return the absolute path to the Blender executable, or null if it cannot be found.
     */
    public static String findExecutablePath() {
        String os = System.getProperty("os.name").toLowerCase();
        String userHome = System.getProperty("user.home");
        String workDir = System.getProperty("user.dir");
        
        if (os.contains("win")) {
            // 1. Try 'where' command on Windows
            String pathFromWhere = executeCommandSilently("where", "blender");
            if (pathFromWhere != null && !pathFromWhere.isEmpty()) {
                String[] lines = pathFromWhere.split("\r?\n");
                for (String p : lines) {
                    p = p.trim();
                    if (!p.isEmpty() && new File(p).canExecute()) {
                        return p;
                    }
                }
            }

            // 2. Scan local portable ./blender_bin directory and subdirectories
            String[] binDirs = {
                "./blender_bin",
                "../blender_bin",
                "../../blender_bin",
                workDir + File.separator + "blender_bin",
                new File(workDir).getParent() + File.separator + "blender_bin",
                userHome + File.separator + "blender_bin"
            };
            for (String bPath : binDirs) {
                if (bPath == null) continue;
                File bDir = new File(bPath);
                if (bDir.exists() && bDir.isDirectory()) {
                    File directExe = new File(bDir, "blender.exe");
                    if (directExe.exists() && directExe.canExecute()) {
                        return directExe.getAbsolutePath();
                    }
                    File[] subdirs = bDir.listFiles();
                    if (subdirs != null) {
                        java.util.Arrays.sort(subdirs, (a, b) -> b.getName().compareToIgnoreCase(a.getName()));
                        for (File sub : subdirs) {
                            if (sub.isDirectory()) {
                                File subExe = new File(sub, "blender.exe");
                                if (subExe.exists() && subExe.canExecute()) {
                                    return subExe.getAbsolutePath();
                                }
                            }
                        }
                    }
                }
            }
            
            // 3. Try common default installation paths on Windows
            String[] commonWinPaths = {
                "C:\\Program Files\\Blender Foundation\\Blender 4.3\\blender.exe",
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
                "C:\\Program Files\\Blender Foundation\\Blender 2.83\\blender.exe",
                userHome + "\\AppData\\Local\\Programs\\Blender Foundation\\Blender 4.2\\blender.exe",
                userHome + "\\AppData\\Local\\Programs\\Blender Foundation\\Blender 4.1\\blender.exe",
                userHome + "\\AppData\\Local\\Programs\\Blender Foundation\\Blender 4.0\\blender.exe",
                userHome + "\\AppData\\Local\\Microsoft\\WindowsApps\\blender.exe"
            };
            for (String p : commonWinPaths) {
                File f = new File(p);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
            
            // 4. Scan the parent Blender Foundation directory in Program Files
            String[] baseDirs = {
                "C:\\Program Files\\Blender Foundation",
                "C:\\Program Files (x86)\\Blender Foundation",
                userHome + "\\AppData\\Local\\Programs\\Blender Foundation"
            };
            for (String base : baseDirs) {
                File foundationDir = new File(base);
                if (foundationDir.exists() && foundationDir.isDirectory()) {
                    File[] subdirs = foundationDir.listFiles();
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
            String[] commonUnixPaths = {
                "/Applications/Blender.app/Contents/MacOS/Blender",
                "/Applications/Blender.app/Contents/MacOS/blender",
                userHome + "/Applications/Blender.app/Contents/MacOS/Blender",
                workDir + "/blender_bin/Blender.app/Contents/MacOS/Blender",
                workDir + "/blender_bin/blender",
                "./blender_bin/blender",
                userHome + "/blender_bin/blender",
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

            // 3. Scan ./blender_bin subdirectories on Linux/Mac
            File bDir = new File("./blender_bin");
            if (bDir.exists() && bDir.isDirectory()) {
                File[] subdirs = bDir.listFiles();
                if (subdirs != null) {
                    for (File sub : subdirs) {
                        if (sub.isDirectory()) {
                            File binExe = new File(sub, "blender");
                            if (binExe.exists() && binExe.canExecute()) {
                                return binExe.getAbsolutePath();
                            }
                            File macExe = new File(sub, "Blender.app/Contents/MacOS/Blender");
                            if (macExe.exists() && macExe.canExecute()) {
                                return macExe.getAbsolutePath();
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Executes a system shell command safely using ProcessBuilder and returns the captured output.
     * Default timeout is 15 seconds.
     *
     * @param command the command and its arguments.
     * @return the command stdout and stderr, or null if execution fails.
     */
    public static String executeCommand(String... command) {
        return executeCommandWithTimeout(15, TimeUnit.SECONDS, command);
    }

    /**
     * Executes a system command with a custom timeout limit.
     *
     * @param timeout duration limit
     * @param unit time unit
     * @param command the command and its arguments
     * @return command output, or null if timed out or failed
     */
    public static String executeCommandWithTimeout(long timeout, TimeUnit unit, String... command) {
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
            
            boolean completed = process.waitFor(timeout, unit);
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
