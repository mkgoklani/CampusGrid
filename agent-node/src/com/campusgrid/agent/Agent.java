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

        String configuredMasterIp = null;
        int configuredMasterPort = 8080;
        boolean autoDiscover = (args == null || args.length == 0 || args[0].trim().isEmpty());

        if (!autoDiscover) {
            configuredMasterIp = args[0].trim();
            if (args.length > 1) {
                try { configuredMasterPort = Integer.parseInt(args[1].trim()); } catch (Exception ignored) {}
            }
        } else {
            java.io.File ipFile = new java.io.File("master_ip.txt");
            if (ipFile.exists()) {
                try {
                    String saved = java.nio.file.Files.readString(ipFile.toPath()).trim();
                    if (!saved.isEmpty() && !saved.equals("127.0.0.1")) {
                        configuredMasterIp = saved;
                        autoDiscover = false;
                        System.out.printf("[CONFIG] Loaded Master IP from master_ip.txt: %s\n", configuredMasterIp);
                    }
                } catch (Exception ignored) {}
            }
        }

        while (true) {
            String targetIp = configuredMasterIp;
            int targetPort = configuredMasterPort;

            if (autoDiscover) {
                System.out.println("[DISCOVERY] Scanning local network (LAN) for Master Node...");
                java.net.InetSocketAddress discovered = com.campusgrid.agent.network.LanDiscoveryClient.discoverMaster(2500);
                if (discovered != null) {
                    targetIp = discovered.getHostString();
                    targetPort = discovered.getPort();
                    System.out.printf("[DISCOVERY] ✔ Auto-discovered CampusGrid Master at %s:%d\n", targetIp, targetPort);
                } else {
                    targetIp = "127.0.0.1";
                    System.out.println("[DISCOVERY] No LAN beacon detected. Trying localhost (127.0.0.1:8080)...");
                }
            }

            System.out.printf("[NETWORK] Initiating connection to Master [%s:%d]...\n", targetIp, targetPort);

            // Connect and block while connected
            MasterConnection connection = new MasterConnection(targetIp, targetPort);
            connection.connect();

            // Monitor connection liveness
            while (connection.isConnected() 
                   && connection.getPayloadListener() != null && connection.getPayloadListener().isRunning()
                   && (connection.getHeartbeatService() == null || connection.getHeartbeatService().isRunning())) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }

            System.out.println("[NETWORK] Connection to Master lost. Reconnecting automatically in 3 seconds...");
            connection.disconnect();

            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {}
        }
    }
}
