package com.campusgrid.agent.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sun.management.OperatingSystemMXBean;

/**
 * High-precision hardware telemetry engine across macOS, Linux, and Windows.
 * Automatically resolves physical CPU cores, logical threads, dedicated vs integrated
 * GPUs, VRAM capacity, and Blender acceleration backends (OPTIX, CUDA, HIP, METAL, ONEAPI).
 */
public class HardwareCollector {

    private static final OperatingSystemMXBean OS_BEAN = 
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    // Statically cached hardware specifications
    private static volatile String cachedCpuModel = null;
    private static volatile String cachedCpuArch = null;
    private static volatile String cachedGpuModel = null;
    private static volatile String cachedGpuComputeType = null;
    private static volatile Boolean cachedGpuAvailable = null;

    /**
     * Gets the system RAM usage in Megabytes (MB).
     */
    public static long getSystemRamUsageMB() {
        long totalMemory = OS_BEAN.getTotalMemorySize();
        long freeMemory = OS_BEAN.getFreeMemorySize();
        long usedMemory = totalMemory - freeMemory;
        return Math.max(0, usedMemory / (1024 * 1024));
    }

    /**
     * Gets the CPU temperature in degrees Celsius from the hardware sensors.
     */
    public static double getCpuTemperature() {
        try {
            Class<?> siClass = Class.forName("oshi.SystemInfo");
            Object si = siClass.getDeclaredConstructor().newInstance();
            Object hal = siClass.getMethod("getHardware").invoke(si);
            Object sensors = hal.getClass().getMethod("getSensors").invoke(hal);
            return (double) sensors.getClass().getMethod("getCpuTemperature").invoke(sensors);
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    /**
     * Gets the CPU architecture with formatted bitness (e.g. "ARM64 (aarch64)", "x86_64 (64-bit)").
     */
    public static String getCpuArchitecture() {
        if (cachedCpuArch != null) return cachedCpuArch;

        String rawArch = System.getProperty("os.arch", "unknown").toLowerCase();
        String formatted;
        if (rawArch.contains("aarch64") || rawArch.contains("arm64")) {
            formatted = "ARM64 (aarch64)";
        } else if (rawArch.contains("amd64") || rawArch.contains("x86_64")) {
            formatted = "x86_64 (64-bit)";
        } else if (rawArch.contains("x86") || rawArch.contains("i386") || rawArch.contains("i686")) {
            formatted = "x86 (32-bit)";
        } else {
            formatted = rawArch.toUpperCase();
        }

        cachedCpuArch = formatted;
        return cachedCpuArch;
    }

    /**
     * Retrieves authentic CPU Model Name with exact physical core and logical thread counts.
     * Examples: "12th Gen Intel(R) Core(TM) i5-12450H (8 Cores, 12 Threads)", "AMD Ryzen 7 7445HS (6 Cores, 12 Threads)".
     */
    public static String getCpuModelName() {
        if (cachedCpuModel != null) return cachedCpuModel;

        String os = System.getProperty("os.name", "").toLowerCase();
        String model = null;
        int logicalThreads = Runtime.getRuntime().availableProcessors();
        int physicalCores = logicalThreads;

        try {
            if (os.contains("mac")) {
                // macOS: query brand string and physical/logical core counts
                String brand = executeCommand("sysctl", "-n", "machdep.cpu.brand_string");
                if (brand != null && !brand.trim().isEmpty() && !brand.trim().equalsIgnoreCase("null")) {
                    model = brand.trim();
                } else {
                    String hwModel = executeCommand("sysctl", "-n", "hw.model");
                    if (hwModel != null && !hwModel.trim().isEmpty()) {
                        model = hwModel.trim();
                    }
                }

                String pCoreStr = executeCommand("sysctl", "-n", "hw.physicalcpu");
                if (pCoreStr != null && pCoreStr.matches("\\d+")) {
                    try { physicalCores = Integer.parseInt(pCoreStr.trim()); } catch (Exception ignored) {}
                }

            } else if (os.contains("linux") || os.contains("unix")) {
                // Linux: read /proc/cpuinfo or lscpu
                File cpuinfo = new File("/proc/cpuinfo");
                if (cpuinfo.exists() && cpuinfo.canRead()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(cpuinfo))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.toLowerCase().startsWith("model name")) {
                                int colon = line.indexOf(':');
                                if (colon >= 0) {
                                    model = line.substring(colon + 1).trim();
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                String lscpu = executeCommand("lscpu");
                if (lscpu != null) {
                    for (String line : lscpu.split("\n")) {
                        if (model == null && line.toLowerCase().startsWith("model name:")) {
                            model = line.substring(line.indexOf(':') + 1).trim();
                        } else if (line.toLowerCase().startsWith("core(s) per socket:")) {
                            try {
                                physicalCores = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                            } catch (Exception ignored) {}
                        }
                    }
                }

            } else if (os.contains("win")) {
                // Windows: Query WMI/CIM for Name, NumberOfCores, and NumberOfLogicalProcessors
                String psCpu = executeCommand("powershell", "-NoProfile", "-Command", 
                    "Get-CimInstance Win32_Processor | Select-Object -First 1 Name, NumberOfCores, NumberOfLogicalProcessors | Format-List");
                
                if (psCpu != null && psCpu.contains("Name")) {
                    for (String line : psCpu.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("Name") && trimmed.contains(":")) {
                            model = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                        } else if (trimmed.startsWith("NumberOfCores") && trimmed.contains(":")) {
                            try { physicalCores = Integer.parseInt(trimmed.substring(trimmed.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
                        } else if (trimmed.startsWith("NumberOfLogicalProcessors") && trimmed.contains(":")) {
                            try { logicalThreads = Integer.parseInt(trimmed.substring(trimmed.indexOf(':') + 1).trim()); } catch (Exception ignored) {}
                        }
                    }
                }

                if (model == null) {
                    String wmic = executeCommand("wmic", "cpu", "get", "Name,NumberOfCores,NumberOfLogicalProcessors", "/value");
                    if (wmic != null) {
                        for (String line : wmic.split("\n")) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("Name=")) {
                                model = trimmed.substring(5).trim();
                            } else if (trimmed.startsWith("NumberOfCores=")) {
                                try { physicalCores = Integer.parseInt(trimmed.substring(14).trim()); } catch (Exception ignored) {}
                            } else if (trimmed.startsWith("NumberOfLogicalProcessors=")) {
                                try { logicalThreads = Integer.parseInt(trimmed.substring(26).trim()); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (model == null || model.isEmpty() || model.equalsIgnoreCase("null")) {
            model = System.getProperty("os.arch").toUpperCase() + " Processor";
        }

        // Clean up excessive whitespace and unwanted boilerplate
        model = model.replaceAll("\\s+", " ").trim();

        // Format cores and threads cleanly
        String coreThreadInfo;
        if (physicalCores == logicalThreads) {
            coreThreadInfo = String.format(" (%d Cores)", physicalCores);
        } else {
            coreThreadInfo = String.format(" (%d Cores, %d Threads)", physicalCores, logicalThreads);
        }

        if (!model.toLowerCase().contains("core") && !model.toLowerCase().contains("thread")) {
            model += coreThreadInfo;
        }

        cachedCpuModel = model;
        return cachedCpuModel;
    }

    /**
     * Retrieves the authentic dedicated GPU Model Name, prioritizing discrete high-performance
     * graphics cards (NVIDIA GeForce RTX, AMD Radeon RX, Intel Arc) over basic integrated display adapters.
     */
    public static String getGpuModelName() {
        if (cachedGpuModel != null) return cachedGpuModel;

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> detectedGpus = new ArrayList<>();

        try {
            // 1. Check NVIDIA SMI directly (works on Windows, Linux)
            String[] nvidiaPaths = {"nvidia-smi", "C:\\Windows\\System32\\nvidia-smi.exe", "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe"};
            for (String nvPath : nvidiaPaths) {
                String nvidia = executeCommand(nvPath, "--query-gpu=gpu_name,memory.total", "--format=csv,noheader");
                if (nvidia != null && !nvidia.trim().isEmpty() && !nvidia.toLowerCase().contains("not found") && !nvidia.toLowerCase().contains("is not recognized")) {
                    for (String line : nvidia.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            detectedGpus.add(trimmed);
                        }
                    }
                    if (!detectedGpus.isEmpty()) break;
                }
            }

            if (os.contains("mac")) {
                // macOS: query system_profiler SPDisplaysDataType
                String profiler = executeCommand("system_profiler", "SPDisplaysDataType");
                if (profiler != null) {
                    String chipset = null;
                    String cores = null;
                    String vram = null;

                    for (String line : profiler.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("Chipset Model:")) {
                            if (chipset != null) {
                                String entry = formatMacGpu(chipset, cores, vram);
                                if (!entry.isEmpty()) detectedGpus.add(entry);
                                cores = null;
                                vram = null;
                            }
                            chipset = trimmed.substring(14).trim();
                        } else if (trimmed.startsWith("Total Number of Cores:")) {
                            cores = trimmed.substring(22).trim();
                        } else if (trimmed.startsWith("VRAM (Total):") || trimmed.startsWith("VRAM (Dynamic, Max):")) {
                            int colon = trimmed.indexOf(':');
                            vram = trimmed.substring(colon + 1).trim();
                        }
                    }
                    if (chipset != null) {
                        detectedGpus.add(formatMacGpu(chipset, cores, vram));
                    }
                }

            } else if (os.contains("linux") || os.contains("unix")) {
                // Linux: lspci display devices
                String lspci = executeCommand("lspci");
                if (lspci != null) {
                    for (String line : lspci.split("\n")) {
                        if (line.matches("(?i).*(vga compatible controller|3d controller|display controller).*")) {
                            int colon = line.indexOf(':');
                            if (colon >= 0) {
                                String rest = line.substring(colon + 1).trim();
                                int subColon = rest.indexOf(':');
                                String gpuName = (subColon >= 0 ? rest.substring(subColon + 1) : rest).trim();
                                detectedGpus.add(gpuName);
                            }
                        }
                    }
                }

            } else if (os.contains("win")) {
                // Windows: Query Win32_VideoController adapters cleanly
                String psGpu = executeCommand("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_VideoController | ForEach-Object { $_.Name }");
                
                if (psGpu != null && !psGpu.trim().isEmpty()) {
                    for (String line : psGpu.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.toLowerCase().startsWith("null")
                            && !trimmed.toLowerCase().contains("at line:") && !trimmed.toLowerCase().contains("char:")) {
                            detectedGpus.add(trimmed);
                        }
                    }
                }

                if (detectedGpus.isEmpty()) {
                    String wmic = executeCommand("wmic", "path", "win32_VideoController", "get", "name", "/value");
                    if (wmic != null) {
                        for (String line : wmic.split("\n")) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("Name=")) {
                                String candidate = trimmed.substring(5).trim();
                                if (!candidate.isEmpty() && !candidate.toLowerCase().contains("char:")) {
                                    detectedGpus.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Filter out virtual display drivers and error artifacts
        List<String> validGpus = new ArrayList<>();
        for (String g : detectedGpus) {
            String lower = g.toLowerCase();
            if (!lower.contains("microsoft basic") && !lower.contains("remote desktop") && 
                !lower.contains("virtualbox") && !lower.contains("vmware") && !lower.contains("citrix") &&
                !lower.contains("spacedesk") && !lower.contains("parsec") &&
                !lower.contains("at line:") && !lower.contains("char:") && !lower.contains("syntaxerror")) {
                validGpus.add(g.replaceAll("\\s+", " ").trim());
            }
        }

        String chosenGpu = null;

        // Prioritize Discrete High-Performance GPUs (NVIDIA RTX/GTX, AMD Radeon RX, Intel Arc)
        for (String g : validGpus) {
            String lower = g.toLowerCase();
            if (lower.contains("rtx") || lower.contains("geforce") || lower.contains("quadro") || lower.contains("tesla") ||
                lower.contains("radeon rx") || lower.contains("radeon pro") || lower.contains("arc a")) {
                chosenGpu = g;
                break;
            }
        }

        // Second pass: Any non-integrated discrete GPU
        if (chosenGpu == null) {
            for (String g : validGpus) {
                String lower = g.toLowerCase();
                if (lower.contains("nvidia") || (lower.contains("radeon") && !lower.contains("graphics")) || lower.contains("arc")) {
                    chosenGpu = g;
                    break;
                }
            }
        }

        // Third pass: Integrated GPU
        if (chosenGpu == null && !validGpus.isEmpty()) {
            chosenGpu = validGpus.get(0);
        }

        if (chosenGpu == null || chosenGpu.isEmpty()) {
            if (os.contains("mac")) {
                chosenGpu = getCpuModelName().split("\\(")[0].trim() + " GPU";
            } else {
                chosenGpu = "Integrated / Host GPU";
            }
        }

        cachedGpuModel = chosenGpu;
        return cachedGpuModel;
    }

    private static String formatMacGpu(String chipset, String cores, String vram) {
        if (chipset == null) return "";
        StringBuilder sb = new StringBuilder(chipset);
        if (cores != null && !cores.isEmpty()) {
            sb.append(" (").append(cores).append(" Cores)");
        } else if (vram != null && !vram.isEmpty()) {
            sb.append(" (").append(vram).append(")");
        }
        return sb.toString();
    }

    /**
     * Determines the optimal GPU compute backend type for Blender rendering.
     * Returns: "OPTIX", "CUDA", "HIP", "METAL", "ONEAPI", or "NONE".
     */
    public static String getGpuComputeType() {
        if (cachedGpuComputeType != null) return cachedGpuComputeType;

        String os = System.getProperty("os.name", "").toLowerCase();
        String gpu = getGpuModelName().toLowerCase();

        String computeType = "NONE";
        if (os.contains("mac")) {
            computeType = "METAL";
        } else if (gpu.contains("rtx") || gpu.contains("ada") || gpu.contains("ampere") || gpu.contains("turing")) {
            // NVIDIA RTX GPUs use hardware-accelerated ray tracing via OPTIX
            computeType = "OPTIX";
        } else if (gpu.contains("nvidia") || gpu.contains("geforce") || gpu.contains("quadro") || gpu.contains("gtx") || gpu.contains("tesla")) {
            computeType = "CUDA";
        } else if (gpu.contains("amd") || gpu.contains("radeon") || gpu.contains("rx ")) {
            computeType = "HIP";
        } else if (gpu.contains("intel") || gpu.contains("arc") || gpu.contains("iris") || gpu.contains("uhd")) {
            computeType = "ONEAPI";
        } else if (isGpuAvailable()) {
            computeType = "CUDA";
        }

        cachedGpuComputeType = computeType;
        return cachedGpuComputeType;
    }

    /**
     * Returns true if a hardware GPU is detected and capable of compute acceleration.
     */
    public static boolean isGpuAvailable() {
        if (cachedGpuAvailable != null) return cachedGpuAvailable;

        String gpu = getGpuModelName().toLowerCase();
        boolean available = !gpu.contains("software render") && !gpu.contains("not installed");
        cachedGpuAvailable = available;
        return cachedGpuAvailable;
    }

    private static String executeCommand(String... command) {
        if (command == null || command.length == 0) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            
            boolean completed = process.waitFor(3, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return null;
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
