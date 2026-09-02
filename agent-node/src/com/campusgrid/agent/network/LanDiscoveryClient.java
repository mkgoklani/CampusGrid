package com.campusgrid.agent.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * CAMPUS GRID - LAN AUTO-DISCOVERY CLIENT
 * 
 * Scans the local WiFi / LAN subnet using UDP broadcast (port 8088)
 * to automatically detect the Master Node IP and TCP port without manual entry.
 */
public class LanDiscoveryClient {

    public static final int DISCOVERY_PORT = 8088;
    public static final String PING_MSG = "CAMPUSGRID_DISCOVERY_PING";
    public static final String PONG_PREFIX = "CAMPUSGRID_DISCOVERY_PONG:";
    public static final String BEACON_PREFIX = "CAMPUSGRID_BEACON:";

    /**
     * Attempts to automatically discover the Master Node on the local network.
     *
     * @param timeoutMs Timeout in milliseconds to wait for UDP responses.
     * @return Discovered InetSocketAddress of the Master Node, or null if not detected.
     */
    public static InetSocketAddress discoverMaster(int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(Math.max(500, timeoutMs));

            // 1. Broadcast Ping to subnet
            byte[] pingBytes = PING_MSG.getBytes(StandardCharsets.UTF_8);
            DatagramPacket pingPacket = new DatagramPacket(
                pingBytes, pingBytes.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
            );
            socket.send(pingPacket);

            // 2. Listen for reply (or beacon)
            byte[] buffer = new byte[512];
            long deadline = System.currentTimeMillis() + timeoutMs;

            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);

                    String text = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8).trim();
                    int port = 8080;

                    if (text.startsWith(PONG_PREFIX)) {
                        try {
                            port = Integer.parseInt(text.substring(PONG_PREFIX.length()).trim());
                        } catch (Exception ignored) {}
                        String host = responsePacket.getAddress().getHostAddress();
                        return new InetSocketAddress(host, port);
                    } else if (text.startsWith(BEACON_PREFIX)) {
                        try {
                            port = Integer.parseInt(text.substring(BEACON_PREFIX.length()).trim());
                        } catch (Exception ignored) {}
                        String host = responsePacket.getAddress().getHostAddress();
                        return new InetSocketAddress(host, port);
                    }
                } catch (java.net.SocketTimeoutException ste) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }

        return null;
    }
}
