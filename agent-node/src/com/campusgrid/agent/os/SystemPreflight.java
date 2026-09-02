package com.campusgrid.agent.os;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import com.campusgrid.agent.blender.BlenderInstaller;
import com.campusgrid.agent.blender.BlenderUtils;
import com.sun.management.OperatingSystemMXBean;

/**
 * System OS-Specific Preflight Diagnostics & Automated Dependency Provisioner.
 * <p>
 * When the Agent Node starts, this engine verifies that all operating system-dependent
 * utilities, shared libraries, GPU compute drivers, and 3D rendering engines are present.
 * If any critical dependencies are missing, it automatically provisions or installs them
 * using native OS package managers or standalone portable bundles.
 * </p>
 */
public class SystemPreflight {

    public static class PreflightResult {
        public boolean allDependenciesSatisfied = true;
        public String osName;
        public String osType;
        public String architecture;
        public String cpuModel;
        public String gpuModel;
        public String gpuComputeType;
        public boolean gpuAvailable;
        public String blenderVersion = "Not Installed";
        public String blenderPath = null;
        public List<String> verifiedComponents = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    /**
     * Executes the full preflight audit and auto-provisions missing OS packages.
     *
     * @param autoInstallBlender If true, automatically downloads/installs Blender if absent.
     * @return PreflightResult containing system status and diagnostic details.
     */
    public static PreflightResult runPreflight(boolean autoInstallBlender) {
        PreflightResult res = new PreflightResult();

        res.osName = System.getProperty("os.name", "Unknown OS");
        res.osType = BlenderUtils.getOsType();
        res.architecture = BlenderUtils.getSystemArch();
        res.cpuModel = HardwareCollector.getCpuModelName();
        res.gpuModel = HardwareCollector.getGpuModelName();
        res.gpuComputeType = HardwareCollector.getGpuComputeType();
        res.gpuAvailable = HardwareCollector.isGpuAvailable();

        System.out.println("\n[PREFLIGHT] ========================================================");
        System.out.println("[PREFLIGHT]   CAMPUSGRID AGENT - OS ENVIRONMENT PREFLIGHT AUDIT     ");
        System.out.println("[PREFLIGHT] ========================================================");
        System.out.printf("[PREFLIGHT] Host OS            : %s (%s)\n", res.osName, res.osType.toUpperCase());
        System.out.printf("[PREFLIGHT] CPU Architecture   : %s (%s)\n", HardwareCollector.getCpuArchitecture(), res.architecture);
        System.out.printf("[PREFLIGHT] CPU Model          : %s\n", res.cpuModel);
        System.out.printf("[PREFLIGHT] GPU Model          : %s\n", res.gpuModel);
        System.out.printf("[PREFLIGHT] Compute Mode       : %s (Hardware Accel: %b)\n", res.gpuComputeType, res.gpuAvailable);
        
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long totalRamMb = osBean.getTotalMemorySize() / (1024 * 1024);
        System.out.printf("[PREFLIGHT] Physical Memory    : %d MB\n", totalRamMb);

        // 1. Verify OS-Specific Command Line & Library Toolchains
        verifyOsToolchain(res);

        // 2. Verify Blender 3D Installation & Auto-Install if Missing
        verifyAndProvisionBlender(res, autoInstallBlender);

        System.out.println("[PREFLIGHT] --------------------------------------------------------");
        if (res.allDependenciesSatisfied) {
            System.out.println("[PREFLIGHT] Status: ALL OS-DEPENDENT FUNCTIONS & LIBRARIES READY");
        } else {
            System.out.println("[PREFLIGHT] Status: READY (Operating with warnings)");
            for (String w : res.warnings) {
                System.out.println("[PREFLIGHT-WARN] - " + w);
            }
        }
        System.out.println("[PREFLIGHT] ========================================================\n");

        return res;
    }

    private static void verifyOsToolchain(PreflightResult res) {
        if (BlenderUtils.isMac()) {
            // macOS checks
            checkCommand(res, "hdiutil", "macOS Disk Image Utility", true);
            checkCommand(res, "sysctl", "Kernel State Prober", true);
            checkCommand(res, "system_profiler", "Hardware Display Profiler", false);
            checkCommand(res, "xattr", "Extended Attribute Manager", true);
            
            String brew = BlenderUtils.executeCommand("which", "brew");
            if (brew != null && !brew.trim().isEmpty()) {
                res.verifiedComponents.add("Homebrew Package Manager (brew)");
                System.out.println("[PREFLIGHT]   [OK] Homebrew Package Manager detected: " + brew.trim());
            }

        } else if (BlenderUtils.isWindows()) {
            // Windows checks
            checkCommand(res, "powershell", "Windows PowerShell Core", true);
            checkCommand(res, "wmic", "Windows Management Instrumentation", false);
            checkCommand(res, "taskkill", "Process Management Utility", true);

            // Check if tar or Expand-Archive is available
            String whereTar = BlenderUtils.executeCommand("where", "tar.exe");
            if (whereTar != null && !whereTar.isEmpty()) {
                res.verifiedComponents.add("Windows Native Tar Utility (tar.exe)");
                System.out.println("[PREFLIGHT]   [OK] Windows Native Tar Utility found");
            }

        } else {
            // Linux checks
            checkCommand(res, "tar", "Tape Archive Extractor", true);
            
            // Check Package Managers
            boolean pkgManagerFound = false;
            String[] linuxPkgManagers = { "apt-get", "snap", "dnf", "pacman", "yum" };
            for (String pm : linuxPkgManagers) {
                String path = BlenderUtils.executeCommand("which", pm);
                if (path != null && !path.trim().isEmpty()) {
                    res.verifiedComponents.add("Package Manager (" + pm + ")");
                    System.out.println("[PREFLIGHT]   [OK] Linux Package Manager detected: " + pm);
                    pkgManagerFound = true;
                    break;
                }
            }
            if (!pkgManagerFound) {
                res.warnings.add("No standard Linux package manager detected in PATH (apt-get/snap/dnf/pacman).");
            }

            // Check Rendering Shared Libraries on Linux
            checkLinuxRenderLibraries(res);
        }
    }

    private static void checkLinuxRenderLibraries(PreflightResult res) {
        String[] criticalLibs = {
            "libGL.so.1", "libXi.so.6", "libXrender.so.1", "libXxf86vm.so.1", "libXfixes.so.3"
        };
        
        List<String> missingLibs = new ArrayList<>();
        String ldconfig = BlenderUtils.executeCommand("ldconfig", "-p");

        for (String lib : criticalLibs) {
            if (ldconfig != null && ldconfig.contains(lib)) {
                res.verifiedComponents.add(lib);
            } else {
                // Check direct /usr/lib and /usr/lib64
                File[] searchPaths = {
                    new File("/usr/lib/x86_64-linux-gnu/" + lib),
                    new File("/usr/lib64/" + lib),
                    new File("/usr/lib/" + lib),
                    new File("/usr/lib/aarch64-linux-gnu/" + lib)
                };
                boolean found = false;
                for (File f : searchPaths) {
                    if (f.exists()) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    missingLibs.add(lib);
                }
            }
        }

        if (missingLibs.isEmpty()) {
            System.out.println("[PREFLIGHT]   [OK] Linux X11 & OpenGL shared libraries verified.");
        } else {
            System.out.println("[PREFLIGHT-NOTICE] Some shared libraries missing: " + String.join(", ", missingLibs));
            res.warnings.add("Missing OpenGL/X11 libraries: " + String.join(", ", missingLibs));
            // Attempt auto-install if apt is available and root
            autoInstallLinuxLibraries();
        }
    }

    private static void autoInstallLinuxLibraries() {
        try {
            String whichApt = BlenderUtils.executeCommand("which", "apt-get");
            if (whichApt != null && !whichApt.isEmpty()) {
                System.out.println("[PREFLIGHT] Attempting auto-installation of missing rendering libraries via apt-get...");
                BlenderUtils.executeCommandWithTimeout(60, "sudo", "apt-get", "install", "-y", 
                    "libgl1", "libxi6", "libxrender1", "libxxf86vm1", "libxfixes3", "libxkbcommon0", "libsm6");
            }
        } catch (Exception ignored) {}
    }

    private static void checkCommand(PreflightResult res, String cmd, String description, boolean critical) {
        String tool = BlenderUtils.isWindows() ? "where" : "which";
        String path = BlenderUtils.executeCommand(tool, cmd);
        if (path != null && !path.trim().isEmpty()) {
            res.verifiedComponents.add(description + " (" + cmd + ")");
            System.out.printf("[PREFLIGHT]   [OK] %s: %s\n", description, path.split("\r?\n")[0].trim());
        } else {
            if (critical) {
                res.allDependenciesSatisfied = false;
                res.warnings.add("Missing critical command tool: " + cmd + " (" + description + ")");
                System.out.printf("[PREFLIGHT-WARN]   [!] Missing tool: %s (%s)\n", cmd, description);
            }
        }
    }

    private static void verifyAndProvisionBlender(PreflightResult res, boolean autoInstall) {
        BlenderInstaller.Status status = BlenderInstaller.getInstallationStatus();
        if (status.isInstalled()) {
            res.blenderVersion = status.getVersion();
            res.blenderPath = status.getExecutablePath();
            res.verifiedComponents.add("Blender 3D Suite (" + res.blenderVersion + ")");
            System.out.printf("[PREFLIGHT]   [OK] Blender 3D Suite: Version %s at %s\n", 
                res.blenderVersion, res.blenderPath);
            return;
        }

        System.out.println("[PREFLIGHT]   [!] Blender 3D is not installed on this system.");
        if (autoInstall) {
            System.out.println("[PREFLIGHT]   [+] Launching automated standalone OS installation pipeline...");
            boolean success = BlenderInstaller.installBlender((pct, msg) -> {
                System.out.printf("[PREFLIGHT-INSTALL] (%.0f%%) %s\n", pct, msg);
            });

            if (success) {
                BlenderInstaller.Status postInstall = BlenderInstaller.getInstallationStatus();
                res.blenderVersion = postInstall.getVersion();
                res.blenderPath = postInstall.getExecutablePath();
                System.out.printf("[PREFLIGHT]   [OK] Blender successfully installed: %s (%s)\n", 
                    res.blenderVersion, res.blenderPath);
            } else {
                res.warnings.add("Blender installation could not be completed automatically. Agent will use pure Java fallback if needed.");
                System.out.println("[PREFLIGHT-WARN] Automated install could not acquire binary. (Manual install: https://www.blender.org/download/)");
            }
        } else {
            res.warnings.add("Blender is not installed. Use Master Node dashboard 'Install Blender' or deploy manually.");
        }
    }
}
