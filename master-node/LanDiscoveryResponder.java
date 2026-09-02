import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * CAMPUS GRID - LAN DISCOVERY RESPONDER (UDP 8088)
 * 
 * Lightweight daemon that enables zero-configuration agent auto-discovery
 * across the campus network / local subnet.
 */
public class LanDiscoveryResponder implements Runnable {

    public static final int DISCOVERY_PORT = 8088;
    public static final String PING_MSG = "CAMPUSGRID_DISCOVERY_PING";
    public static final String PONG_PREFIX = "CAMPUSGRID_DISCOVERY_PONG:";
    public static final String BEACON_PREFIX = "CAMPUSGRID_BEACON:";

    private final int masterTcpPort;
    private volatile boolean running = false;
    private Thread responderThread;
    private Thread beaconThread;
    private DatagramSocket socket;

    public LanDiscoveryResponder(int masterTcpPort) {
        this.masterTcpPort = masterTcpPort;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setBroadcast(true);

            responderThread = new Thread(this, "LanDiscoveryResponder");
            responderThread.setDaemon(true);
            responderThread.start();

            // Periodic 3-second LAN beacon
            beaconThread = new Thread(this::runBeaconLoop, "LanDiscoveryBeacon");
            beaconThread.setDaemon(true);
            beaconThread.start();

            System.out.printf("[LAN-DISCOVERY] Auto-discovery beacon active on UDP port %d (Target TCP: %d).\n",
                DISCOVERY_PORT, masterTcpPort);
        } catch (Exception e) {
            System.err.println("[LAN-DISCOVERY-WARN] Could not bind UDP discovery port " + DISCOVERY_PORT + ": " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    public void run() {
        byte[] buffer = new byte[512];
        while (running && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                if (PING_MSG.equals(msg)) {
                    String reply = PONG_PREFIX + masterTcpPort;
                    byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket response = new DatagramPacket(
                        replyBytes, replyBytes.length, packet.getAddress(), packet.getPort()
                    );
                    socket.send(response);
                }
            } catch (Exception ignored) {}
        }
    }

    private void runBeaconLoop() {
        while (running && socket != null && !socket.isClosed()) {
            try {
                String beacon = BEACON_PREFIX + masterTcpPort;
                byte[] bytes = beacon.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                    bytes, bytes.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                );
                socket.send(packet);
                Thread.sleep(3000);
            } catch (Exception ignored) {}
        }
    }
}
