import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * UDP Discovery Beacon on the Master Node.
 * <p>
 * Listens on UDP port 8888 for incoming discovery requests from Agent nodes
 * and provides immediate responses with the Master Node's local LAN IP and TCP port.
 * Also periodically broadcasts announcements so worker agents can passively discover the Master.
 * </p>
 */
public class MasterDiscoveryBeacon {

    public static final int DEFAULT_DISCOVERY_PORT = 8888;
    public static final String DISCOVERY_REQUEST_HEADER = "CAMPUSGRID_DISCOVER_REQUEST";
    public static final String MASTER_ANNOUNCE_HEADER = "CAMPUSGRID_MASTER_ANNOUNCE";

    private final int discoveryPort;
    private final int agentTcpPort;
    private final int dashboardHttpPort;
    private final String masterName;

    private DatagramSocket datagramSocket;
    private Thread listenerThread;
    private Thread broadcasterThread;
    private volatile boolean running = false;

    public MasterDiscoveryBeacon(int agentTcpPort, int dashboardHttpPort) {
        this(DEFAULT_DISCOVERY_PORT, agentTcpPort, dashboardHttpPort, "CampusGrid-Master");
    }

    public MasterDiscoveryBeacon(int discoveryPort, int agentTcpPort, int dashboardHttpPort, String masterName) {
        this.discoveryPort = discoveryPort;
        this.agentTcpPort = agentTcpPort;
        this.dashboardHttpPort = dashboardHttpPort;
        this.masterName = masterName != null ? masterName : "CampusGrid-Master";
    }

    /**
     * Starts the UDP Discovery listener and background broadcaster.
     */
    public synchronized void start() throws IOException {
        if (running) return;
        running = true;

        try {
            datagramSocket = new DatagramSocket(null);
            datagramSocket.setReuseAddress(true);
            datagramSocket.setBroadcast(true);
            datagramSocket.bind(new InetSocketAddress("0.0.0.0", discoveryPort));
        } catch (BindException e) {
            System.err.printf("[DISCOVERY-WARN] UDP port %d already in use. Discovery responder running in client-broadcast only mode.\n", discoveryPort);
            datagramSocket = new DatagramSocket();
            datagramSocket.setBroadcast(true);
        }

        // 1. Thread to listen for discovery probes from agents
        listenerThread = new Thread(this::runListenerLoop, "Master-Discovery-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        // 2. Thread to periodically broadcast master presence across LAN
        broadcasterThread = new Thread(this::runBroadcasterLoop, "Master-Discovery-Broadcaster");
        broadcasterThread.setDaemon(true);
        broadcasterThread.start();

        System.out.printf("[DISCOVERY] UDP LAN Auto-Discovery Beacon active on port %d (Broadcasting TCP Port: %d)\n",
            discoveryPort, agentTcpPort);
    }

    /**
     * Stops the discovery service cleanly.
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;

        if (datagramSocket != null && !datagramSocket.isClosed()) {
            datagramSocket.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        if (broadcasterThread != null) {
            broadcasterThread.interrupt();
        }
        System.out.println("[DISCOVERY] Master UDP Discovery Beacon stopped.");
    }

    private void runListenerLoop() {
        byte[] buffer = new byte[2048];
        while (running && datagramSocket != null && !datagramSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                datagramSocket.receive(packet);

                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (msg.startsWith(DISCOVERY_REQUEST_HEADER)) {
                    InetAddress requesterAddr = packet.getAddress();
                    int requesterPort = packet.getPort();

                    String lanIp = resolveLocalLanIp(requesterAddr);
                    String response = String.format("%s | IP: %s | TCP_PORT: %d | HTTP_PORT: %d | NAME: %s",
                        MASTER_ANNOUNCE_HEADER, lanIp, agentTcpPort, dashboardHttpPort, masterName);

                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket respPacket = new DatagramPacket(respBytes, respBytes.length, requesterAddr, requesterPort);
                    datagramSocket.send(respPacket);

                    System.out.printf("[DISCOVERY] Handled discovery probe from [%s:%d] ➔ Announced Master at %s:%d\n",
                        requesterAddr.getHostAddress(), requesterPort, lanIp, agentTcpPort);
                }
            } catch (SocketException e) {
                if (!running) break;
            } catch (Exception e) {
                if (running) {
                    System.err.println("[DISCOVERY-ERR] Error in discovery listener: " + e.getMessage());
                }
            }
        }
    }

    private void runBroadcasterLoop() {
        while (running && datagramSocket != null && !datagramSocket.isClosed()) {
            try {
                String lanIp = resolveLocalLanIp(null);
                String announcement = String.format("%s | IP: %s | TCP_PORT: %d | HTTP_PORT: %d | NAME: %s",
                    MASTER_ANNOUNCE_HEADER, lanIp, agentTcpPort, dashboardHttpPort, masterName);

                byte[] data = announcement.getBytes(StandardCharsets.UTF_8);

                // Broadcast to global broadcast
                try {
                    DatagramPacket globalPacket = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), discoveryPort);
                    datagramSocket.send(globalPacket);
                } catch (Exception ignored) {}

                // Broadcast to all active interface broadcast addresses
                for (InetAddress bcast : getBroadcastAddresses()) {
                    try {
                        DatagramPacket ifacePacket = new DatagramPacket(data, data.length, bcast, discoveryPort);
                        datagramSocket.send(ifacePacket);
                    } catch (Exception ignored) {}
                }

                Thread.sleep(3000); // Broadcast every 3 seconds

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { break; }
                }
            }
        }
    }

    /**
     * Resolves the machine's primary non-loopback IPv4 LAN address.
     */
    public static String resolveLocalLanIp(InetAddress targetAddress) {
        if (targetAddress != null && !targetAddress.isLoopbackAddress() && !targetAddress.isAnyLocalAddress()) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(targetAddress, 80);
                InetAddress localAddr = socket.getLocalAddress();
                if (localAddr instanceof Inet4Address && !localAddr.isLoopbackAddress() && !localAddr.isAnyLocalAddress()) {
                    return localAddr.getHostAddress();
                }
            } catch (Exception ignored) {}
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}

        return "127.0.0.1";
    }

    public static List<InetAddress> getBroadcastAddresses() {
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
