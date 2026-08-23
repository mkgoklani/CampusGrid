package com.campusgrid.agent.os;

import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;

/**
 * Accesses hardware metrics (CPU Temperature and System RAM Usage)
 * using JVM OperatingSystemMXBean and dynamic OSHI reflection if available.
 */
public class HardwareCollector {

    private static final OperatingSystemMXBean OS_BEAN = 
        (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    /**
     * Gets the system RAM usage in Megabytes (MB).
     * Represents the real physical RAM used by the operating system.
     *
     * @return the used system memory in MB.
     */
    public static long getSystemRamUsageMB() {
        long totalMemory = OS_BEAN.getTotalMemorySize();
        long freeMemory = OS_BEAN.getFreeMemorySize();
        long usedMemory = totalMemory - freeMemory;
        return Math.max(0, usedMemory / (1024 * 1024));
    }

    /**
     * Gets the CPU temperature in degrees Celsius from the hardware sensors.
     *
     * @return the CPU temperature, or 0.0 if not available.
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
}
