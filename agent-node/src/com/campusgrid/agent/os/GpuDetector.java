package com.campusgrid.agent.os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import com.campusgrid.agent.blender.BlenderUtils;

/**
 * GPU DETECTOR FOR CAMPUSGRID AGENT NODES
 * 
 * Auto-detects dedicated GPUs and hardware acceleration APIs
 * (NVIDIA OptiX / CUDA, Apple Silicon Metal, AMD HIP / ROCm, Intel oneAPI)
 * via Blender 3D query or host OS drivers.
 */
public class GpuDetector {

    private static volatile String cachedGpuInfo = null;
    private static volatile boolean checked = false;

    /**
     * Detects available GPU devices and hardware acceleration APIs on the host.
     *
     * @return Formatted GPU string (e.g. "NVIDIA GeForce RTX 3050 (OPTIX)") or "None / CPU"
     */
    public static synchronized String getGpuInfo() {
        if (checked && cachedGpuInfo != null) {
            return cachedGpuInfo;
        }

        String blenderPath = BlenderUtils.findExecutablePath();
        if (blenderPath != null) {
            try {
                // Query Blender directly for certified compute devices
                String pyQuery = "import bpy; " +
                    "prefs = bpy.context.preferences.addons.get('cycles'); " +
                    "cprefs = prefs.preferences if prefs else None; " +
                    "(cprefs and cprefs.get_devices()); " +
                    "devs = [d for d in cprefs.devices if d.type in ('OPTIX', 'CUDA', 'HIP', 'METAL', 'ONEAPI')] if cprefs else []; " +
                    "print('CAMPUSGRID_GPU:', devs[0].name + ' (' + devs[0].type + ')' if devs else 'CPU_ONLY')";

                ProcessBuilder pb = new ProcessBuilder(blenderPath, "-b", "--python-expr", pyQuery);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                String detected = null;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("CAMPUSGRID_GPU:")) {
                            String info = line.substring(line.indexOf("CAMPUSGRID_GPU:") + 15).trim();
                            if (!info.equals("CPU_ONLY") && !info.isEmpty()) {
                                detected = info;
                            }
                        }
                    }
                }
                p.waitFor();

                if (detected != null) {
                    cachedGpuInfo = detected;
                    checked = true;
                    return cachedGpuInfo;
                }
            } catch (Exception ignored) {}
        }

        // Fallback: OS Driver queries
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("wmic", "path", "win32_videocontroller", "get", "name");
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.equalsIgnoreCase("name")) {
                            if (line.toLowerCase().contains("nvidia") || line.toLowerCase().contains("rtx") || line.toLowerCase().contains("gtx") || line.toLowerCase().contains("radeon")) {
                                cachedGpuInfo = line;
                                checked = true;
                                return cachedGpuInfo;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        cachedGpuInfo = "CPU";
        checked = true;
        return cachedGpuInfo;
    }
}
