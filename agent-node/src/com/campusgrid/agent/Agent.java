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

        // Instantiate connection manager and attempt connection
        MasterConnection connection = new MasterConnection(masterIp);
        connection.connect();
    }
}
