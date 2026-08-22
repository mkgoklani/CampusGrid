package com.campusgrid.agent.os;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Monitors user activity on Ubuntu using the xprintidle command.
 * Determines whether the workstation is idle and available for computational tasks.
 */
public class IdleDetector {

    /**
     * Executes the 'xprintidle' command using ProcessBuilder to find user idle time.
     *
     * @return user idle time in milliseconds, or -1 if the command is unavailable/fails.
     */
    public static long getIdleTime() {
        try {
            ProcessBuilder pb = new ProcessBuilder("xprintidle");
            Process process = pb.start();

            long idleTime = -1;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    idleTime = Long.parseLong(line.trim());
                }
            }

            process.waitFor();

            if (idleTime >= 0) {
                System.out.println("[IDLE] Idle time: " + idleTime + " ms");
            } else {
                System.out.println("[IDLE] xprintidle unavailable");
            }
            return idleTime;
        } catch (Exception e) {
            System.out.println("[IDLE] xprintidle unavailable");
            return -1;
        }
    }

    /**
     * Determines if a user is active on the workstation.
     * The user is considered active if the idle time is less than 2000 milliseconds.
     *
     * @return true if the user is active, false if idle or command is unavailable.
     */
    public static boolean isUserActive() {
        long idleTime = getIdleTime();
        boolean active = idleTime >= 0 && idleTime < 2000;
        if (idleTime >= 0) {
            if (active) {
                System.out.println("[IDLE] User Active");
            } else {
                System.out.println("[IDLE] User Idle");
            }
        }
        return active;
    }
}
