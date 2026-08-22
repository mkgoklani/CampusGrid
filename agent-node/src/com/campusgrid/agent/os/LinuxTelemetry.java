package com.campusgrid.agent.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gathers system telemetry metrics from the host operating system.
 * Handles Ubuntu/Linux sysfs diagnostics, standard sensors command parses, and 
 * high-fidelity dynamic task load-based fallbacks for macOS M1 / VM sandboxes.
 */
public class LinuxTelemetry {

    public static volatile boolean isExecutingTask = false;

    private static final Pattern TEMP_PATTERN = 
        Pattern.compile("(?:Package id 0|Core 0|temp1|CPU|TdegC):\\s*[+-]?\\s*(\\d+)(?:\\.\\d+)?");

    /**
     * Retrieves the CPU temperature. Checks direct Linux sysfs files, standard lm-sensors
     * outputs, or falls back to load-based simulations on macOS M1/VMs to ensure active load metrics.
     *
     * @return the CPU temperature formatted as "XX°C".
     */
    public static String getCpuTemperature() {
        String os = System.getProperty("os.name").toLowerCase();

        // 1. Linux Sysfs Direct Read Approach (Highly reliable on Ubuntu VMs/Hosts, no lm-sensors package required)
        if (os.contains("linux") || os.contains("unix")) {
            String[] sysfsZones = new String[]{
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/class/thermal/thermal_zone1/temp"
            };

            for (String zonePath : sysfsZones) {
                File tempFile = new File(zonePath);
                if (tempFile.exists() && tempFile.canRead()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(tempFile))) {
                        String line = reader.readLine();
                        if (line != null) {
                            int milliTemp = Integer.parseInt(line.trim());
                            int celsius = milliTemp / 1000;
                            if (celsius > 0 && celsius < 150) {
                                return celsius + "°C";
                            }
                        }
                    } catch (Exception e) {
                        // Fallback to next approach
                    }
                }
            }

            // 2. Linux "sensors" Command Execution
            try {
                ProcessBuilder pb = new ProcessBuilder("sensors");
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher matcher = TEMP_PATTERN.matcher(line);
                        if (matcher.find()) {
                            return matcher.group(1) + "°C";
                        }
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                // Fallback to simulation
            }
        }

        // 3. Dynamic Thermal Simulation Fallback (For macOS M1 or VM sandboxes where sensors are unavailable)
        // Generates realistic temperature levels: idle nodes run cool (38-42°C), busy nodes run warm (54-59°C)
        int baseTemp = isExecutingTask ? 54 : 38;
        int fluctuation = (int) (Math.random() * 6); // 0 to 5 degrees variance
        int currentTemp = baseTemp + fluctuation;

        return currentTemp + "°C";
    }
}
