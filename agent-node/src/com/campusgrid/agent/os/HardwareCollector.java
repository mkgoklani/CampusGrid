package com.campusgrid.agent.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.sun.management.OperatingSystemMXBean;

/**
 * Accesses hardware metrics (CPU Model, Architecture, Core Count, GPU Model,
 * GPU Compute Capabilities, Temperature, and System RAM Usage)
 * across macOS, Linux, and Windows platforms.
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
     * Retrieves the authentic CPU Model Name across macOS, Linux, and Windows.
     * Examples: "Apple M1 Max (10 Cores)", "13th Gen Intel(R) Core(TM) i7-13700H (14 Cores)", "AMD Ryzen 9 5900X (12 Cores)".
     */
    public static String getCpuModelName() {
        if (cachedCpuModel != null) return cachedCpuModel;

        String os = System.getProperty("os.name", "").toLowerCase();
        String model = null;
        int cores = Runtime.getRuntime().availableProcessors();

        try {
            if (os.contains("mac")) {
                // macOS: query machdep.cpu.brand_string or hw.model
                String brand = executeCommand("sysctl", "-n", "machdep.cpu.brand_string");
                if (brand != null && !brand.trim().isEmpty() && !brand.trim().equalsIgnoreCase("null")) {
                    model = brand.trim();
                } else {
                    String hwModel = executeCommand("sysctl", "-n", "hw.model");
                    if (hwModel != null && !hwModel.trim().isEmpty()) {
                        model = hwModel.trim();
                    }
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

                if (model == null) {
                    String lscpu = executeCommand("lscpu");
                    if (lscpu != null) {
                        for (String line : lscpu.split("\n")) {
                            if (line.toLowerCase().startsWith("model name:")) {
                                model = line.substring(line.indexOf(':') + 1).trim();
                                break;
                            }
                        }
                    }
                }
            } else if (os.contains("win")) {
                // Windows: query wmic or PowerShell or Registry
                String wmic = executeCommand("wmic", "cpu", "get", "Name", "/value");
                if (wmic != null && wmic.contains("Name=")) {
                    for (String line : wmic.split("\n")) {
                        if (line.trim().startsWith("Name=")) {
                            model = line.trim().substring(5).trim();
                            break;
                        }
                    }
                }

                if (model == null) {
                    String ps = executeCommand("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name");
                    if (ps != null && !ps.trim().isEmpty()) {
                        model = ps.trim();
                    }
                }
            }
        } catch (Exception ignored) {}

        if (model == null || model.isEmpty() || model.equalsIgnoreCase("null")) {
            model = System.getProperty("os.arch").toUpperCase() + " Processor";
        }

        // Clean up excessive whitespace
        model = model.replaceAll("\\s+", " ").trim();

        // Append core count if not already present in string
        if (!model.toLowerCase().contains("core")) {
            model += String.format(" (%d Cores)", cores);
        }

        cachedCpuModel = model;
        return cachedCpuModel;
    }

    /**
     * Retrieves the authentic GPU Model Name across macOS, Linux, and Windows.
     * Examples: "Apple M1 (7 Cores)", "NVIDIA GeForce RTX 4080 (16GB VRAM)", "AMD Radeon RX 6700 XT", "Intel Iris Xe Graphics".
     */
    public static String getGpuModelName() {
        if (cachedGpuModel != null) return cachedGpuModel;

        String os = System.getProperty("os.name", "").toLowerCase();
        String gpu = null;

        try {
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
                            chipset = trimmed.substring(14).trim();
                        } else if (trimmed.startsWith("Total Number of Cores:")) {
                            cores = trimmed.substring(22).trim();
                        } else if (trimmed.startsWith("VRAM (Total):") || trimmed.startsWith("VRAM (Dynamic, Max):")) {
                            int colon = trimmed.indexOf(':');
                            vram = trimmed.substring(colon + 1).trim();
                        }
                    }

                    if (chipset != null) {
                        gpu = chipset;
                        if (cores != null) {
                            gpu += " (" + cores + " Cores)";
                        } else if (vram != null) {
                            gpu += " (" + vram + ")";
                        }
                    }
                }
            } else if (os.contains("linux") || os.contains("unix")) {
                // 1. Try NVIDIA SMI
                String nvidia = executeCommand("nvidia-smi", "--query-gpu=gpu_name,memory.total", "--format=csv,noheader");
                if (nvidia != null && !nvidia.trim().isEmpty() && !nvidia.toLowerCase().contains("not found")) {
                    gpu = nvidia.trim();
                }

                // 2. Try lspci
                if (gpu == null) {
                    String lspci = executeCommand("lspci");
                    if (lspci != null) {
                        for (String line : lspci.split("\n")) {
                            if (line.matches("(?i).*(vga compatible controller|3d controller|display controller).*")) {
                                int colon = line.indexOf(':');
                                if (colon >= 0) {
                                    String rest = line.substring(colon + 1).trim();
                                    int subColon = rest.indexOf(':');
                                    gpu = (subColon >= 0 ? rest.substring(subColon + 1) : rest).trim();
                                    break;
                                }
                            }
                        }
                    }
                }
            } else if (os.contains("win")) {
                // Windows: wmic path win32_VideoController
                String wmic = executeCommand("wmic", "path", "win32_VideoController", "get", "name", "/value");
                if (wmic != null && wmic.contains("Name=")) {
                    for (String line : wmic.split("\n")) {
                        if (line.trim().startsWith("Name=")) {
                            String candidate = line.trim().substring(5).trim();
                            if (!candidate.isEmpty()) {
                                gpu = candidate;
                                break;
                            }
                        }
                    }
                }

                if (gpu == null) {
                    String ps = executeCommand("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_VideoController).Name");
                    if (ps != null && !ps.trim().isEmpty()) {
                        gpu = ps.trim().split("\n")[0].trim();
                    }
                }
            }
        } catch (Exception ignored) {}

        if (gpu == null || gpu.isEmpty() || gpu.equalsIgnoreCase("null")) {
            // Fallback: Check if Apple Silicon or integrated
            if (os.contains("mac")) {
                gpu = getCpuModelName().split("\\(")[0].trim() + " GPU";
            } else {
                gpu = "Integrated / Software Render GPU";
            }
        }

        // Clean up
        gpu = gpu.replaceAll("\\s+", " ").trim();
        cachedGpuModel = gpu;
        return cachedGpuModel;
    }

    /**
     * Determines the optimal GPU compute backend type for Blender rendering.
     * Returns: "METAL", "CUDA", "OPTIX", "HIP", "ONEAPI", or "NONE".
     */
    public static String getGpuComputeType() {
        if (cachedGpuComputeType != null) return cachedGpuComputeType;

        String os = System.getProperty("os.name", "").toLowerCase();
        String gpu = getGpuModelName().toLowerCase();

        String computeType = "NONE";
        if (os.contains("mac")) {
            // Apple Silicon and modern macOS AMD GPUs support METAL
            computeType = "METAL";
        } else if (gpu.contains("nvidia") || gpu.contains("geforce") || gpu.contains("quadro") || gpu.contains("rtx") || gpu.contains("gtx")) {
            computeType = (gpu.contains("rtx")) ? "OPTIX" : "CUDA";
        } else if (gpu.contains("amd") || gpu.contains("radeon") || gpu.contains("rx ")) {
            computeType = "HIP";
        } else if (gpu.contains("intel") || gpu.contains("arc ") || gpu.contains("iris")) {
            computeType = "ONEAPI";
        } else if (isGpuAvailable()) {
            computeType = "CUDA"; // Generic default for discrete
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
