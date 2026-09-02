package com.campusgrid.agent;

import com.campusgrid.agent.network.MasterConnection;

/**
 * The main class for the CampusGrid Agent.
 * <p>
 * This agent runs on campus machines, detects idle status, reports telemetry
 * to the Master node, and executes payloads received from the Master.
 * </p>
 */
public class Agent {

    /**
     * The main entry point of the CampusGrid Agent.
     * Starts the agent, processes command line arguments, and connects to the Master.
     *
     * @param args command line arguments. The first argument must be the Master node IP address.
     */
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        System.out.println("==================================");
        System.out.println("CampusGrid Distributed Agent");
        System.out.println("==================================");

        String masterIp = null;
        int masterPort = 8080;

        if (args != null && args.length > 0 && !args[0].trim().isEmpty()) {
            masterIp = args[0].trim();
            if (args.length > 1) {
                try { masterPort = Integer.parseInt(args[1].trim()); } catch (Exception ignored) {}
            }
        } else {
            System.out.println("[DISCOVERY] No Master IP provided. Scanning local network (LAN) for Master Node...");
            java.net.InetSocketAddress discovered = com.campusgrid.agent.network.LanDiscoveryClient.discoverMaster(2500);
            if (discovered != null) {
                masterIp = discovered.getHostString();
                masterPort = discovered.getPort();
                System.out.printf("[DISCOVERY] ✔ Auto-discovered CampusGrid Master at %s:%d\n", masterIp, masterPort);
            } else {
                masterIp = "127.0.0.1";
                System.out.println("[DISCOVERY] No LAN Master beacon detected within timeout. Falling back to localhost (127.0.0.1:8080).");
            }
        }

        System.out.printf("[NETWORK] Initiating connection to Master [%s:%d]...\n", masterIp, masterPort);

        // Instantiate connection manager and attempt connection persistently
        MasterConnection connection = new MasterConnection(masterIp, masterPort);
        while (true) {
            connection.connect();
            
            // Block main thread while connection is active
            while (connection.isConnected()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            System.out.println("[NETWORK] Master connection lost or closed. Re-initiating connection loop...");
            connection.disconnect(); // Clean up socket/streams before retry
            
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {}
        }
    }
}
