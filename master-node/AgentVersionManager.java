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
                // Explicitly inject agent_version.properties into JAR root
                JarEntry verEntry = new JarEntry("agent_version.properties");
                jos.putNextEntry(verEntry);
                String verProps = String.format("agent.version=%s\nagent.build=%d\nagent.timestamp=%d\n",
                    this.currentVersion, this.currentBuild, System.currentTimeMillis());
                jos.write(verProps.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jos.closeEntry();

                addDirectoryToJar(jos, classesDir, classesDir);
            }

            System.out.printf("[VERSION-MANAGER] ✓ Packaged executable %s (v%s, Build %d, Size: %d bytes)\n",
                targetJarFile.getName(), currentVersion, currentBuild, targetJarFile.length());

            // Generate standalone agent.bat with embedded binary payload
            generateStandaloneAgentBat(targetJarFile, new File("agent.bat"));
            return true;

        } catch (Exception e) {
            System.err.println("[VERSION-MANAGER-ERR] Failed creating agent.jar: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generates a single standalone Windows agent.bat script embedding the JAR payload
     * and auto-download logic, so Windows users only need agent.bat without needing agent.jar.
     */
    public boolean generateStandaloneAgentBat(File sourceJarFile, File targetBatFile) {
        if (!sourceJarFile.exists()) return false;
        try {
            byte[] jarBytes = Files.readAllBytes(sourceJarFile.toPath());
            String base64 = java.util.Base64.getEncoder().encodeToString(jarBytes);

            StringBuilder sb = new StringBuilder();
            sb.append("@echo off\r\n");
            sb.append("setlocal enabledelayedexpansion\r\n\r\n");
            sb.append("title CampusGrid Agent Node (v").append(this.currentVersion).append(")\r\n");
            sb.append("cd /d \"%~dp0\"\r\n\r\n");
            sb.append("echo ================================================================\r\n");
            sb.append("echo           CAMPUSGRID DISTRIBUTED COMPUTE AGENT NODE             \r\n");
            sb.append("echo                   Version: v").append(this.currentVersion).append(" (Build ").append(this.currentBuild).append(")\r\n");
            sb.append("echo ================================================================\r\n");
            sb.append("echo.\r\n\r\n");

            sb.append(":: 1. Check if Java 17+ is installed\r\n");
            sb.append("where java >nul 2>nul\r\n");
            sb.append("if %errorlevel% neq 0 (\r\n");
            sb.append("    echo [WARNING] Java command not found in system PATH.\r\n");
            sb.append("    echo Checking standard installation directories...\r\n");
            sb.append("    set \"FOUND_JAVA=\"\r\n");
            sb.append("    for /d %%D in (\"C:\\Program Files\\Eclipse Adoptium\\jdk-*\" \"C:\\Program Files\\Eclipse Adoptium\\jre-*\" \"C:\\Program Files\\Java\\jdk-*\" \"C:\\Program Files\\Microsoft\\jdk-*\") do (\r\n");
            sb.append("        if exist \"%%D\\bin\\java.exe\" set \"FOUND_JAVA=%%D\\bin\\java.exe\"\r\n");
            sb.append("    )\r\n");
            sb.append("    if defined FOUND_JAVA (\r\n");
            sb.append("        echo [OK] Found Java at: !FOUND_JAVA!\r\n");
            sb.append("        set \"JAVA_CMD=!FOUND_JAVA!\"\r\n");
            sb.append("    ) else (\r\n");
            sb.append("        echo.\r\n");
            sb.append("        echo [ERROR] Java (Version 17+) is required to run CampusGrid Agent.\r\n");
            sb.append("        echo.\r\n");
            sb.append("        echo Would you like to automatically install Java 17 using Winget? (Y/N)\r\n");
            sb.append("        set /p \"INSTALL_CHOICE=Choice [Y/N]: \"\r\n");
            sb.append("        if /i \"!INSTALL_CHOICE!\"==\"Y\" (\r\n");
            sb.append("            echo.\r\n");
            sb.append("            echo [INSTALL] Installing Eclipse Adoptium OpenJDK 17...\r\n");
            sb.append("            winget install EclipseAdoptium.Temurin.17.JRE\r\n");
            sb.append("            echo.\r\n");
            sb.append("            echo [NOTE] Java installed! Please re-run agent.bat.\r\n");
            sb.append("            pause\r\n");
            sb.append("            exit /b 0\r\n");
            sb.append("        ) else (\r\n");
            sb.append("            echo Please manually install Java 17 from: https://adoptium.net\r\n");
            sb.append("            pause\r\n");
            sb.append("            exit /b 1\r\n");
            sb.append("        )\r\n");
            sb.append("    )\r\n");
            sb.append(") else (\r\n");
            sb.append("    set \"JAVA_CMD=java\"\r\n");
            sb.append(")\r\n\r\n");

            sb.append(":: 2. Resolve Master Node Address\r\n");
            sb.append("set \"CONFIG_FILE=%~dp0master_node.txt\"\r\n");
            sb.append("set \"DEFAULT_IP=100.66.175.104:8080\"\r\n\r\n");
            sb.append("if not \"%~1\"==\"\" (\r\n");
            sb.append("    set \"MASTER_ADDR=%~1\"\r\n");
            sb.append(") else (\r\n");
            sb.append("    if exist \"%CONFIG_FILE%\" (\r\n");
            sb.append("        set /p SAVED_IP=<\"%CONFIG_FILE%\"\r\n");
            sb.append("        if not \"!SAVED_IP!\"==\"\" set \"DEFAULT_IP=!SAVED_IP!\"\r\n");
            sb.append("    )\r\n");
            sb.append("    echo ----------------------------------------------------------------\r\n");
            sb.append("    echo Enter the Master Node IP:Port (Tailscale or Network IP)\r\n");
            sb.append("    echo Default: [!DEFAULT_IP!]\r\n");
            sb.append("    echo ----------------------------------------------------------------\r\n");
            sb.append("    set /p \"USER_INPUT=Master Address (Press Enter for default): \"\r\n");
            sb.append("    if \"!USER_INPUT!\"==\"\" (\r\n");
            sb.append("        set \"MASTER_ADDR=!DEFAULT_IP!\"\r\n");
            sb.append("    ) else (\r\n");
            sb.append("        set \"MASTER_ADDR=!USER_INPUT!\"\r\n");
            sb.append("    )\r\n");
            sb.append("    echo !MASTER_ADDR!>\"%CONFIG_FILE%\"\r\n");
            sb.append(")\r\n\r\n");

            sb.append("echo !MASTER_ADDR! | findstr /c:\":\" >nul\r\n");
            sb.append("if %errorlevel% neq 0 set \"MASTER_ADDR=!MASTER_ADDR!:8080\"\r\n\r\n");

            sb.append("for /f \"tokens=1,2 delims=:\" %%A in (\"!MASTER_ADDR!\") do (\r\n");
            sb.append("    set \"MASTER_HOST=%%A\"\r\n");
            sb.append("    set \"MASTER_PORT=%%B\"\r\n");
            sb.append(")\r\n\r\n");

            sb.append(":: 3. Prepare standalone agent.jar runtime\r\n");
            sb.append("set \"TARGET_JAR=%~dp0agent.jar\"\r\n\r\n");
            sb.append(":: Extract embedded binary payload if local agent.jar is not found\r\n");
            sb.append("if not exist \"%TARGET_JAR%\" (\r\n");
            sb.append("    echo [EXTRACT] Extracting embedded CampusGrid Agent binaries...\r\n");
            sb.append("    powershell -NoProfile -Command \"$lines = Get-Content -LiteralPath '%~f0'; $start = $false; $b64 = ''; foreach($line in $lines){ if($line -match '^::==START_B64_PAYLOAD==$'){ $start = $true; continue }; if($start){ $b64 += $line } }; if($b64.Length -gt 0){ [IO.File]::WriteAllBytes('%TARGET_JAR%', [Convert]::FromBase64String($b64)); exit 0 } else { exit 1 }\"\r\n");
            sb.append(")\r\n\r\n");

            sb.append("echo.\r\n");
            sb.append("echo ================================================================\r\n");
            sb.append("echo Starting CampusGrid Agent Node connecting to: !MASTER_ADDR!\r\n");
            sb.append("echo ================================================================\r\n");
            sb.append("echo.\r\n\r\n");

            sb.append("\"!JAVA_CMD!\" -jar \"%TARGET_JAR%\" !MASTER_ADDR!\r\n\r\n");
            sb.append("echo.\r\n");
            sb.append("echo [AGENT] Process exited.\r\n");
            sb.append("pause\r\n");
            sb.append("exit /b 0\r\n\r\n");

            sb.append("::==START_B64_PAYLOAD==\r\n");
            // Break base64 into lines of 120 chars for Windows batch safety
            int len = base64.length();
            for (int i = 0; i < len; i += 120) {
                sb.append(base64, i, Math.min(i + 120, len)).append("\r\n");
            }

            Files.writeString(targetBatFile.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.printf("[VERSION-MANAGER] ✓ Packaged standalone %s (Size: %d bytes)\n",
                targetBatFile.getName(), targetBatFile.length());
            return true;
        } catch (Exception e) {
            System.err.println("[VERSION-MANAGER-ERR] Failed generating standalone agent.bat: " + e.getMessage());
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
            System.out.printf("[CLI] Successfully incremented and packaged agent.jar & agent.bat: v%s (Build %d)\n",
                newVer, manager.getCurrentBuild());
        } else {
            File target = new File("agent.jar");
            manager.packageAgentJar(target);
            System.out.printf("[CLI] Successfully packaged %s & agent.bat (v%s, Build %d, Size: %d bytes)\n",
                target.getName(), manager.getCurrentVersion(), manager.getCurrentBuild(), target.length());
        }
    }
}
