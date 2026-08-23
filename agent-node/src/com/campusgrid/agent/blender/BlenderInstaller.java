package com.campusgrid.agent.blender;

/**
 * Handles detection and verification of the Blender installation on the worker node.
 */
public class BlenderInstaller {

    /**
     * Represents the installation status of Blender on the host machine.
     */
    public static class Status {
        private final boolean installed;
        private final String version;
        private final String executablePath;

        /**
         * Constructs a new Blender installation status record.
         *
         * @param installed      true if Blender is installed, false otherwise.
         * @param version        the detected version of Blender, or "Unknown".
         * @param executablePath the absolute path to the Blender executable, or null.
         */
        public Status(boolean installed, String version, String executablePath) {
            this.installed = installed;
            this.version = version;
            this.executablePath = executablePath;
        }

        /**
         * Checks if Blender is installed on the host system.
         *
         * @return true if Blender is installed, false otherwise.
         */
        public boolean isInstalled() {
            return installed;
        }

        /**
         * Gets the detected version of Blender.
         *
         * @return the version string (e.g. "3.6.2"), or "Unknown" if not resolved.
         */
        public String getVersion() {
            return version;
        }

        /**
         * Gets the path to the Blender executable.
         *
         * @return the absolute executable path, or null if not found.
         */
        public String getExecutablePath() {
            return executablePath;
        }

        @Override
        public String toString() {
            return String.format("BlenderStatus[Installed=%b, Version=%s, Path=%s]", 
                installed, version, executablePath);
        }
    }

    /**
     * Detects if Blender is installed on the system, checks its version, and returns its status.
     * Uses ProcessBuilder only (via BlenderUtils command executors).
     * Supports Ubuntu/Linux.
     *
     * @return the current Blender installation status.
     */
    public static Status getInstallationStatus() {
        String path = BlenderUtils.findExecutablePath();
        if (path == null) {
            return new Status(false, "Unknown", null);
        }

        // Check version using the executable path
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
}
