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
        if (args == null || args.length == 0) {
            System.out.println("Usage:");
            System.out.println("java Agent <MASTER_IP>");
            System.exit(1);
        }

        System.out.println("==================================");
        System.out.println("CampusGrid Agent");
        System.out.println("Starting Agent...");
        System.out.println("==================================");

        String masterIp = args[0];

        // Instantiate connection manager and attempt connection persistently
        MasterConnection connection = new MasterConnection(masterIp);
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
