package com.campusgrid.master;

public class SystemTest {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("--- Booting CampusGrid Master ---");

        // 1. Start the HTTP UI Server
        DashboardServer.main(args);

        // 2. Start the WebSocket Broadcaster on port 8081
        TelemetryBroadcaster broadcaster = new TelemetryBroadcaster(8081);
        broadcaster.start();

        System.out.println("Waiting 5 seconds... Open http://localhost:8080 in your browser NOW!");
        
        // Give you time to open the browser
        Thread.sleep(5000);

        // 3. Create simulated JSON payloads (matching your DTOs)
        String dummyTelemetry = "{" +
            "\"type\":\"TELEMETRY\"," +
            "\"nodeId\":\"AGENT-102\"," +
            "\"status\":\"BUSY\"," +
            "\"cpuUsage\":88," +
            "\"ramUsage\":8192," +
            "\"temperature\":74," +
            "\"currentJobId\":\"104\"" +
        "}";

        String dummyProgress = "{" +
            "\"type\":\"PROGRESS\"," +
            "\"jobId\":\"104\"," +
            "\"jobType\":\"Blender Render\"," +
            "\"completedFrames\":65," +
            "\"totalFrames\":100" +
        "}";

        // 4. Blast the data to the UI
        System.out.println("Blasting telemetry to UI...");
        broadcaster.broadcastUpdate(dummyTelemetry);
        
        Thread.sleep(1000); // Wait 1 second
        
        System.out.println("Blasting progress update to UI...");
        broadcaster.broadcastUpdate(dummyProgress);
    }
}