package com.campusgrid.agent.os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gathers system telemetry metrics from the host operating system (Linux).
 * <p>
 * This class is responsible for executing the Linux 'sensors' command to monitor
 * host hardware telemetry, specifically the CPU temperature.
 * </p>
 */
public class LinuxTelemetry {

    private static final Pattern TEMP_PATTERN = Pattern.compile("Package id 0:.*?[+-]?\\s*(\\d+)(?:\\.\\d+)?");

    /**
     * Executes the 'sensors' command on Linux, parses its output, and returns
     * the numeric CPU temperature value.
     *
     * @return the CPU temperature formatted as "XX°C", or "UNKNOWN" if parsing or execution fails.
     */
    public static String getCpuTemperature() {
        System.out.println("[LINUX] Reading sensors...");
        try {
            ProcessBuilder pb = new ProcessBuilder("sensors");
            Process process = pb.start();

            String temp = "UNKNOWN";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = TEMP_PATTERN.matcher(line);
                    if (matcher.find()) {
                        temp = matcher.group(1) + "°C";
                    }
                }
            }

            process.waitFor();

            if ("UNKNOWN".equals(temp)) {
                System.out.println("[LINUX] Unable to read sensors.");
            } else {
                System.out.println("[LINUX] CPU Temperature: " + temp);
            }
            return temp;

        } catch (Exception e) {
            System.out.println("[LINUX] Unable to read sensors.");
            return "UNKNOWN";
        }
    }
}
