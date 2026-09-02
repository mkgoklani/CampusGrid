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

    public static volatile String CURRENT_VERSION = "1.0.2";
    public static volatile int CURRENT_BUILD = 102;

    static {
        // 1. Check System Properties (-Dagent.version / -Dagent.build)
        String sysVer = System.getProperty("agent.version");
        String sysBuild = System.getProperty("agent.build");
        if (sysVer != null && !sysVer.isEmpty()) CURRENT_VERSION = sysVer;
        if (sysBuild != null && !sysBuild.isEmpty()) {
            try { CURRENT_BUILD = Integer.parseInt(sysBuild); } catch (Exception ignored) {}
        }

        // 2. Check agent_version.properties in classpath
        try (java.io.InputStream is = Agent.class.getResourceAsStream("/agent_version.properties")) {
            if (is != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(is);
                CURRENT_VERSION = props.getProperty("agent.version", CURRENT_VERSION);
                CURRENT_BUILD = Integer.parseInt(props.getProperty("agent.build", String.valueOf(CURRENT_BUILD)));
            }
        } catch (Exception ignored) {}

        // 3. Check JAR Manifest Attributes
        try {
            java.net.URL classUrl = Agent.class.getResource("Agent.class");
            if (classUrl != null && classUrl.toString().startsWith("jar:")) {
                java.net.JarURLConnection connection = (java.net.JarURLConnection) classUrl.openConnection();
                java.util.jar.Manifest manifest = connection.getManifest();
                if (manifest != null) {
                    String mVer = manifest.getMainAttributes().getValue("Agent-Version");
                    String mBuild = manifest.getMainAttributes().getValue("Agent-Build");
                    if (mVer != null && !mVer.isEmpty()) CURRENT_VERSION = mVer;
                    if (mBuild != null && !mBuild.isEmpty()) {
                        try { CURRENT_BUILD = Integer.parseInt(mBuild); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * The main entry point of the CampusGrid Agent.
     * Starts the agent, processes command line arguments, and connects to the Master.
     *
     * @param args command line arguments. The first argument must be the Master node IP address.
     */
    public static void main(String[] args) {
        System.out.println("==================================");
        System.out.printf("CampusGrid Agent v%s (Build %d)\n", CURRENT_VERSION, CURRENT_BUILD);
        System.out.println("Starting Agent...");
        System.out.println("==================================");

        // 0. OS-Specific Preflight Verification & Dependency Audit
        boolean autoProvision = false;
        if (args != null) {
            for (String arg : args) {
                if ("--auto-install".equalsIgnoreCase(arg) || "--provision".equalsIgnoreCase(arg)) {
                    autoProvision = true;
                    break;
                }
            }
        }
        com.campusgrid.agent.os.SystemPreflight.runPreflight(autoProvision);

        String masterIp = null;
        int port = MasterConnection.MASTER_PORT;

        if (args != null && args.length > 0 && !args[0].equalsIgnoreCase("auto") && !args[0].equalsIgnoreCase("discover")) {
            masterIp = args[0];
            if (masterIp.contains(":")) {
                String[] parts = masterIp.split(":", 2);
                masterIp = parts[0];
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            } else if (args.length > 1) {
                try {
                    port = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
        } else {
            // Zero-Configuration LAN Auto-Discovery
            com.campusgrid.agent.network.MasterFinder.DiscoveredMaster discovered = 
                com.campusgrid.agent.network.MasterFinder.discoverMasterLoop();
            if (discovered != null) {
                masterIp = discovered.getIpAddress();
                port = discovered.getTcpPort();
            } else {
                masterIp = "127.0.0.1";
            }
        }

        System.out.printf("[NETWORK] Initiating TCP connection to Master Node at %s:%d...\n", masterIp, port);

        // Instantiate connection manager and attempt connection persistently
        MasterConnection connection = new MasterConnection(masterIp, port);
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
