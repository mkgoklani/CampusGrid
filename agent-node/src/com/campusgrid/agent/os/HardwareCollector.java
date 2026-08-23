package com.campusgrid.agent.os;

import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.GlobalMemory;
import oshi.hardware.Sensors;

/**
 * Accesses hardware metrics (CPU Temperature and System RAM Usage)
 * using the Operating System and Hardware Information (OSHI) library.
 */
public class HardwareCollector {
    private static final SystemInfo si = new SystemInfo();
    private static final HardwareAbstractionLayer hal = si.getHardware();

    /**
     * Gets the system RAM usage in Megabytes (MB).
     * Represents the real physical RAM used by the operating system.
     *
     * @return the used system memory in MB.
     */
    public static long getSystemRamUsageMB() {
        GlobalMemory memory = hal.getMemory();
        long totalMemory = memory.getTotal();
        long availableMemory = memory.getAvailable();
        long usedMemory = totalMemory - availableMemory;
        
        // Convert Bytes to Megabytes
        return usedMemory / (1024 * 1024);
    }

    /**
     * Gets the CPU temperature in degrees Celsius from the hardware sensors.
     *
     * @return the CPU temperature, or 0.0 if not available.
     */
    public static double getCpuTemperature() {
        Sensors sensors = hal.getSensors();
        return sensors.getCpuTemperature();
    }
}
