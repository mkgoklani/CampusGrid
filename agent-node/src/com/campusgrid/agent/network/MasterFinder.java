package com.campusgrid.agent.network;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Client-side UDP Auto-Discovery engine for CampusGrid Agent Nodes.
 * <p>
 * Automatically locates the active Master Node on the local network without
 * requiring manual IP configuration or user typing.
 * </p>
 */
public class MasterFinder {

    public static final int DEFAULT_DISCOVERY_PORT = 8888;
    public static final String DISCOVERY_REQUEST_HEADER = "CAMPUSGRID_DISCOVER_REQUEST";
    public static final String MASTER_ANNOUNCE_HEADER = "CAMPUSGRID_MASTER_ANNOUNCE";

    public static class DiscoveredMaster {
        private final String ipAddress;
        private final int tcpPort;
        private final int httpPort;
        private final String name;

        public DiscoveredMaster(String ipAddress, int tcpPort, int httpPort, String name) {
            this.ipAddress = ipAddress;
            this.tcpPort = tcpPort;
            this.httpPort = httpPort;
            this.name = name;
        }

        public String getIpAddress() { return ipAddress; }
        public int getTcpPort() { return tcpPort; }
        public int getHttpPort() { return httpPort; }
        public String getName() { return name; }

        @Override
        public String toString() {
            return String.format("%s at %s:%d (HTTP: %d)", name, ipAddress, tcpPort, httpPort);
        }
    }

    /**
     * Attempts to discover the Master Node on the local LAN within the given timeout.
     *
     * @param timeoutMs Timeout in milliseconds to wait for a Master announcement.
     * @return DiscoveredMaster metadata, or null if no Master responded.
     */
    public static DiscoveredMaster discoverMaster(int timeoutMs) {
        return discoverMaster(DEFAULT_DISCOVERY_PORT, timeoutMs);
    }

    public static DiscoveredMaster discoverMaster(int discoveryPort, int timeoutMs) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);

            // 1. Send discovery probe to global broadcast and interface broadcast addresses
            byte[] probeData = DISCOVERY_REQUEST_HEADER.getBytes(StandardCharsets.UTF_8);

            try {
                DatagramPacket globalPacket = new DatagramPacket(probeData, probeData.length,
                    InetAddress.getByName("255.255.255.255"), discoveryPort);
                socket.send(globalPacket);
            } catch (Exception ignored) {}

            for (InetAddress bcast : getBroadcastAddresses()) {
                try {
                    DatagramPacket ifacePacket = new DatagramPacket(probeData, probeData.length,
                        bcast, discoveryPort);
                    socket.send(ifacePacket);
                } catch (Exception ignored) {}
            }

            // Also probe localhost in case testing on single PC
            try {
                DatagramPacket loopbackPacket = new DatagramPacket(probeData, probeData.length,
                    InetAddress.getByName("127.0.0.1"), discoveryPort);
                socket.send(loopbackPacket);
            } catch (Exception ignored) {}

            // 2. Listen for announcements
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[2048];

            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) Math.max(1, deadline - System.currentTimeMillis());
                socket.setSoTimeout(remaining);
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(responsePacket);
                } catch (SocketTimeoutException e) {
                    break;
                }

                String response = new String(responsePacket.getData(), responsePacket.getOffset(),
                    responsePacket.getLength(), StandardCharsets.UTF_8).trim();

                if (response.startsWith(MASTER_ANNOUNCE_HEADER)) {
                    return parseAnnouncement(response, responsePacket.getAddress().getHostAddress());
                }
            }

        } catch (Exception e) {
            System.err.println("[DISCOVERY-ERR] Error during LAN discovery: " + e.getMessage());
        }

        return null;
    }

    /**
     * Loops continuously until an active Master Node is discovered on the local network.
     *
     * @return DiscoveredMaster metadata.
     */
    public static DiscoveredMaster discoverMasterLoop() {
        System.out.println("\n[DISCOVERY] No Master IP specified.");
        System.out.println("[DISCOVERY] Initiating zero-configuration LAN Auto-Discovery (UDP Port 8888)...");

        int attempts = 1;
        while (true) {
            System.out.printf("[DISCOVERY] Probing local network for active Master Node (Attempt #%d)...\n", attempts++);
            DiscoveredMaster master = discoverMaster(2500);

            if (master != null) {
                System.out.println("\n======================================================================");
                System.out.println("  [DISCOVERY] ✓ Master Node Auto-Discovered on Local LAN!");
                System.out.println("  [DISCOVERY] Master Name : " + master.getName());
                System.out.println("  [DISCOVERY] Master IP   : " + master.getIpAddress());
                System.out.println("  [DISCOVERY] TCP Port    : " + master.getTcpPort());
                System.out.println("  [DISCOVERY] HTTP API    : http://" + master.getIpAddress() + ":" + master.getHttpPort() + "/");
                System.out.println("======================================================================\n");
                return master;
            }

            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static DiscoveredMaster parseAnnouncement(String packetStr, String fallbackIp) {
        String ip = fallbackIp;
        int tcpPort = 8080;
        int httpPort = 8081;
        String name = "CampusGrid-Master";

        String[] parts = packetStr.split("\\|");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("IP:")) {
                String candidate = part.substring(3).trim();
                // If candidate is not loopback, prioritize it
                if (!candidate.equals("127.0.0.1") && !candidate.equalsIgnoreCase("localhost") && !candidate.isEmpty()) {
                    ip = candidate;
                }
            } else if (part.startsWith("TCP_PORT:")) {
                try { tcpPort = Integer.parseInt(part.substring(9).trim()); } catch (Exception ignored) {}
            } else if (part.startsWith("HTTP_PORT:")) {
                try { httpPort = Integer.parseInt(part.substring(10).trim()); } catch (Exception ignored) {}
            } else if (part.startsWith("NAME:")) {
                name = part.substring(5).trim();
            }
        }

        return new DiscoveredMaster(ip, tcpPort, httpPort, name);
    }

    private static List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> broadcastList = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null) {
                        broadcastList.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {}
        return broadcastList;
    }
}
