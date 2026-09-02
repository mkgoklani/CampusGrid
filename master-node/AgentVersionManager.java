import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.jar.*;

/**
 * Manages Agent Node versioning, incremental build tracking,
 * and automated packaging of the agent executable JAR file.
 */
public class AgentVersionManager {

    private static final String VERSION_FILE = "agent_version.properties";
    private static final String DEFAULT_VERSION = "1.0.1";
    private static final int DEFAULT_BUILD = 101;

    private volatile String currentVersion = DEFAULT_VERSION;
    private volatile int currentBuild = DEFAULT_BUILD;
    private volatile long lastUpdatedTimestamp = System.currentTimeMillis();

    private final Object lock = new Object();

    public AgentVersionManager() {
        loadVersionProperties();
    }

    /**
     * Loads the latest version and build number from agent_version.properties if present.
     */
    public void loadVersionProperties() {
        synchronized (lock) {
            File file = new File(VERSION_FILE);
            if (file.exists()) {
                Properties props = new Properties();
                try (InputStream is = new FileInputStream(file)) {
                    props.load(is);
                    this.currentVersion = props.getProperty("agent.version", DEFAULT_VERSION);
                    this.currentBuild = Integer.parseInt(props.getProperty("agent.build", String.valueOf(DEFAULT_BUILD)));
                    this.lastUpdatedTimestamp = Long.parseLong(props.getProperty("agent.timestamp", String.valueOf(System.currentTimeMillis())));
                    System.out.printf("[VERSION-MANAGER] Loaded Agent Version: v%s (Build %d)\n", currentVersion, currentBuild);
                    return;
                } catch (Exception e) {
                    System.err.println("[VERSION-MANAGER-WARN] Failed reading version properties: " + e.getMessage());
                }
            }
            saveVersionProperties();
        }
    }

    /**
     * Saves the current version configuration to agent_version.properties.
     */
    public void saveVersionProperties() {
        synchronized (lock) {
            File file = new File(VERSION_FILE);
            Properties props = new Properties();
            props.setProperty("agent.version", currentVersion);
            props.setProperty("agent.build", String.valueOf(currentBuild));
            props.setProperty("agent.timestamp", String.valueOf(lastUpdatedTimestamp));
            try (OutputStream os = new FileOutputStream(file)) {
                props.store(os, "CampusGrid Agent Auto-Versioning Metadata");
            } catch (Exception e) {
                System.err.println("[VERSION-MANAGER-ERR] Failed writing version properties: " + e.getMessage());
            }
        }
    }

    /**
     * Increments the build number and semver, and automatically builds a fresh agent.jar.
     *
     * @return New version string formatted with build number.
     */
    public String incrementAndPackageAgent() {
        synchronized (lock) {
            this.currentBuild++;
            this.lastUpdatedTimestamp = System.currentTimeMillis();

            // Increment minor semver or append build
            String[] parts = this.currentVersion.split("\\.");
            if (parts.length == 3) {
                try {
                    int patch = Integer.parseInt(parts[2]) + 1;
                    this.currentVersion = parts[0] + "." + parts[1] + "." + patch;
                } catch (NumberFormatException e) {
                    this.currentVersion = "1.0." + currentBuild;
                }
            } else {
                this.currentVersion = "1.0." + currentBuild;
            }

            saveVersionProperties();
            System.out.printf("[VERSION-MANAGER] ➔ Incremented Agent to v%s (Build %d)\n", currentVersion, currentBuild);

            // Package agent.jar into current working directory and dist directory
            packageAgentJar(new File("agent.jar"));
            return currentVersion;
        }
    }

    /**
     * Checks if a connected agent node has an outdated build or version.
     *
     * @param agentVersion Running agent's reported version string.
     * @param agentBuildNumber Running agent's reported build integer.
     * @return true if agent is older than Master's latest agent build.
     */
    public boolean isAgentOutdated(String agentVersion, int agentBuildNumber) {
        if (agentBuildNumber > 0 && agentBuildNumber < this.currentBuild) {
            return true;
        }
        if (agentVersion == null || agentVersion.trim().isEmpty() || "Unknown".equalsIgnoreCase(agentVersion)) {
            return true;
        }
        return compareSemver(agentVersion, this.currentVersion) < 0;
    }

    /**
     * Compares two semantic version strings (e.g. "1.0.0" vs "1.0.1").
     * Returns negative if v1 < v2, 0 if equal, positive if v1 > v2.
     */
    public static int compareSemver(String v1, String v2) {
        if (v1 == null) v1 = "0.0.0";
        if (v2 == null) v2 = "0.0.0";

        v1 = v1.replace("v", "").trim();
        v2 = v2.replace("v", "").trim();

        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);

        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? parseSafeInt(p1[i]) : 0;
            int n2 = i < p2.length ? parseSafeInt(p2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    private static int parseSafeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Packages a standalone executable agent.jar containing all agent-node and common-lib classes.
     * Searches class root directories: "out", "build/classes", or classpath.
     */
    public boolean packageAgentJar(File targetJarFile) {
        File classesDir = new File("out");
        if (!classesDir.exists() || !classesDir.isDirectory()) {
            classesDir = new File("build/classes/java/main");
        }
        if (!classesDir.exists() || !classesDir.isDirectory()) {
            classesDir = new File("bin");
        }

        if (!classesDir.exists()) {
            System.err.println("[VERSION-MANAGER-WARN] No compiled classes directory found (out/ or build/). Skipping JAR package creation.");
            return false;
        }

        try {
            if (targetJarFile.getParentFile() != null) {
                targetJarFile.getParentFile().mkdirs();
            }

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.campusgrid.agent.Agent");
            manifest.getMainAttributes().put(new Attributes.Name("Agent-Version"), this.currentVersion);
            manifest.getMainAttributes().put(new Attributes.Name("Agent-Build"), String.valueOf(this.currentBuild));

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(targetJarFile), manifest)) {
                addDirectoryToJar(jos, classesDir, classesDir);
            }

            System.out.printf("[VERSION-MANAGER] ✓ Packaged executable %s (v%s, Build %d, Size: %d bytes)\n",
                targetJarFile.getName(), currentVersion, currentBuild, targetJarFile.length());
            return true;

        } catch (Exception e) {
            System.err.println("[VERSION-MANAGER-ERR] Failed creating agent.jar: " + e.getMessage());
            return false;
        }
    }

    private void addDirectoryToJar(JarOutputStream jos, File rootDir, File sourceDir) throws IOException {
        File[] files = sourceDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                addDirectoryToJar(jos, rootDir, file);
            } else if (file.getName().endsWith(".class") || file.getName().endsWith(".properties")) {
                String relativePath = rootDir.toPath().relativize(file.toPath()).toString().replace("\\", "/");
                // Only include common-lib and agent-node classes in the agent.jar bundle
                if (relativePath.startsWith("com/campusgrid/agent") || relativePath.startsWith("com/campusgrid/core") || relativePath.endsWith(".properties")) {
                    JarEntry entry = new JarEntry(relativePath);
                    entry.setTime(file.lastModified());
                    jos.putNextEntry(entry);
                    try (InputStream is = new FileInputStream(file)) {
                        is.transferTo(jos);
                    }
                    jos.closeEntry();
                }
            }
        }
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
        saveVersionProperties();
    }

    public int getCurrentBuild() {
        return currentBuild;
    }

    public void setCurrentBuild(int currentBuild) {
        this.currentBuild = currentBuild;
        saveVersionProperties();
    }

    public long getLastUpdatedTimestamp() {
        return lastUpdatedTimestamp;
    }

    public static void main(String[] args) {
        AgentVersionManager manager = new AgentVersionManager();
        if (args != null && args.length > 0 && "increment".equalsIgnoreCase(args[0])) {
            String newVer = manager.incrementAndPackageAgent();
            System.out.printf("[CLI] Successfully incremented and packaged agent.jar: v%s (Build %d)\n",
                newVer, manager.getCurrentBuild());
        } else {
            File target = new File("agent.jar");
            manager.packageAgentJar(target);
            System.out.printf("[CLI] Successfully packaged %s (v%s, Build %d, Size: %d bytes)\n",
                target.getName(), manager.getCurrentVersion(), manager.getCurrentBuild(), target.length());
        }
    }
}
