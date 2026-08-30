import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import com.campusgrid.core.*;


/**
 * CAMPUS GRID - EMBEDDED DASHBOARD REST & WEBSOCKET SERVER
 * 
 * Provides an embedded HTTP/REST control API and high-speed WebSocket telemetry
 * stream for the CampusGrid web dashboard with zero external dependencies.
 * 
 * REST Endpoints:
 * - GET  /api/cluster/status : Cluster node summary and hardware telemetry
 * - GET  /api/jobs           : Active and completed job progress
 * - POST /api/jobs/submit    : Submit new distributed workloads
 * - POST /api/jobs/cancel    : Cancel running jobs
 * 
 * WebSocket Endpoint:
 * - ws://localhost:8082/ws/telemetry : Real-time telemetry push to connected Web UIs
 */
public class DashboardServer {

    private static final int DEFAULT_HTTP_PORT = 8081;
    private static final int DEFAULT_WS_PORT = 8082;

    private final JobManager jobManager;
    private final WorkerRegistry workerRegistry;
    private final int httpPort;
    private final int wsPort;

    private HttpServer httpServer;
    private WebSocketBroadcaster wsBroadcaster;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(12);

    public DashboardServer(JobManager jobManager, WorkerRegistry workerRegistry) {
        this(jobManager, workerRegistry, DEFAULT_HTTP_PORT, DEFAULT_WS_PORT);
    }

    private final java.util.concurrent.atomic.AtomicInteger jobSeq = new java.util.concurrent.atomic.AtomicInteger(1);

    public DashboardServer(JobManager jobManager, WorkerRegistry workerRegistry, int httpPort, int wsPort) {
        this.jobManager = jobManager;
        this.workerRegistry = workerRegistry;
        this.httpPort = httpPort;
        this.wsPort = wsPort;
    }

    /**
     * Starts the HTTP REST server and WebSocket broadcaster daemon.
     */
    public synchronized void start() throws IOException {
        // 1. Initialize HTTP REST Server & Web UI
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/api/cluster/status", new ClusterStatusHandler());
        httpServer.createContext("/api/jobs", new JobsHandler());
        httpServer.createContext("/api/jobs/submit", new SubmitJobHandler());
        httpServer.createContext("/api/jobs/cancel", new CancelJobHandler());
        httpServer.createContext("/api/nodes/install-blender", new InstallBlenderHandler());
        httpServer.createContext("/api/nodes/toggle-assignment", new ToggleAssignmentHandler());
        httpServer.createContext("/download/agent.jar", new AgentJarDownloadHandler());
        httpServer.createContext("/download/blender", new BlenderDownloadHandler());
        httpServer.createContext("/output", new OutputFileHandler());
        httpServer.createContext("/", new StaticWebHandler());
        httpServer.setExecutor(threadPool);
        httpServer.start();
        System.out.println("[DASHBOARD-HTTP] Web Dashboard active at http://localhost:" + httpPort + "/");
        System.out.println("[DASHBOARD-HTTP] REST API active at http://localhost:" + httpPort + "/api/");

        // 2. Initialize WebSocket Telemetry Broadcaster
        wsBroadcaster = new WebSocketBroadcaster(wsPort, this::generateTelemetrySnapshotJson);
        wsBroadcaster.start();
        System.out.println("[DASHBOARD-WS] WebSocket streaming active at ws://localhost:" + wsPort + "/ws/telemetry");
    }

    /**
     * Stops the HTTP and WebSocket servers gracefully.
     */
    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
        }
        if (wsBroadcaster != null) {
            wsBroadcaster.stop();
            wsBroadcaster = null;
        }
        threadPool.shutdownNow();
        System.out.println("[DASHBOARD-SERVER] Stopped.");
    }

    // ========================================================================
    // JSON SERIALIZATION HELPERS
    // ========================================================================

    private String generateTelemetrySnapshotJson() {
        WorkerRegistry.ClusterSummary summary = workerRegistry.getClusterSummary();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        sb.append("\"cluster\":{");
        sb.append("\"total\":").append(summary.total).append(",");
        sb.append("\"available\":").append(summary.available).append(",");
        sb.append("\"busy\":").append(summary.busy).append(",");
        sb.append("\"offline\":").append(summary.offline).append(",");
        sb.append("\"evicted\":").append(summary.evicted);
        sb.append("},");

        sb.append("\"workers\":[");
        Collection<WorkerState> workers = workerRegistry.getAllWorkers();
        int count = 0;
        for (WorkerState w : workers) {
            if (count++ > 0) sb.append(",");
            sb.append("{");
            sb.append("\"workerId\":\"").append(escapeJson(w.getWorkerId())).append("\",");
            sb.append("\"ipAddress\":\"").append(escapeJson(w.getIpAddress())).append("\",");
            sb.append("\"status\":\"").append(w.getStatus()).append("\",");
            sb.append("\"osName\":\"").append(escapeJson(w.getOsName())).append("\",");
            sb.append("\"blenderInstalled\":").append(w.isBlenderInstalled()).append(",");
            sb.append("\"blenderVersion\":\"").append(escapeJson(w.getBlenderVersion())).append("\",");
            sb.append("\"taskAssignmentEnabled\":").append(w.isTaskAssignmentEnabled()).append(",");
            sb.append("\"installProgress\":").append(String.format(Locale.US, "%.1f", w.getInstallProgress())).append(",");
            sb.append("\"installMsg\":").append(w.getInstallMsg() != null ? "\"" + escapeJson(w.getInstallMsg()) + "\"" : "null").append(",");
            sb.append("\"cpuTemp\":").append(w.getCpuTemperature()).append(",");
            sb.append("\"cpuUsage\":").append(String.format(Locale.US, "%.1f", w.getCpuUsagePercent())).append(",");
            sb.append("\"ramUsage\":").append(String.format(Locale.US, "%.2f", w.getRamUsagePercent())).append(",");
            sb.append("\"currentJobId\":").append(w.getCurrentJobId() != null ? "\"" + escapeJson(w.getCurrentJobId()) + "\"" : "null").append(",");
            sb.append("\"currentTaskId\":").append(w.getCurrentTaskId() != null ? "\"" + escapeJson(w.getCurrentTaskId()) + "\"" : "null").append(",");
            sb.append("\"assignedFrames\":").append(w.getAssignedFrameRange() != null ? "\"" + escapeJson(w.getAssignedFrameRange()) + "\"" : "null").append(",");
            sb.append("\"currentRenderFrame\":").append(w.getCurrentRenderFrame()).append(",");
            sb.append("\"totalRenderFrames\":").append(w.getTotalRenderFrames()).append(",");
            sb.append("\"currentRenderProgress\":").append(String.format(Locale.US, "%.1f", w.getCurrentRenderProgress())).append(",");
            sb.append("\"lastHeartbeat\":").append(w.getLastHeartbeatTimestamp());
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String generateJobsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        int count = 0;
        for (Job job : jobManager.getAllJobs().values()) {
            if (count++ > 0) sb.append(",");
            String blendPath = (job.getParameters() != null && job.getParameters().containsKey("blendFilePath"))
                ? job.getParameters().get("blendFilePath").toString() : "test.blend";
            String blendName = (job.getParameters() != null && job.getParameters().containsKey("blendFileName"))
                ? job.getParameters().get("blendFileName").toString() : new File(blendPath).getName();
            boolean cleanUp = (job.getParameters() != null && job.getParameters().containsKey("deleteFramesAfterStitch"))
                && Boolean.parseBoolean(job.getParameters().get("deleteFramesAfterStitch").toString());

            String videoUrl = job.getCompiledVideoUrl();
            if (videoUrl == null || videoUrl.isEmpty()) {
                File defaultVideo = new File("./output/" + job.getJobId() + "/" + job.getJobId() + "_animation.mp4");
                if (defaultVideo.exists()) {
                    videoUrl = "/output/" + job.getJobId() + "/" + job.getJobId() + "_animation.mp4";
                }
            }

            sb.append("{");
            sb.append("\"jobId\":\"").append(escapeJson(job.getJobId())).append("\",");
            sb.append("\"jobName\":\"").append(escapeJson(job.getJobName())).append("\",");
            sb.append("\"workloadType\":\"").append(escapeJson(job.getWorkloadType())).append("\",");
            sb.append("\"blendFileName\":\"").append(escapeJson(blendName)).append("\",");
            sb.append("\"blendFilePath\":\"").append(escapeJson(blendPath)).append("\",");
            sb.append("\"cleanUpFrames\":").append(cleanUp).append(",");
            sb.append("\"videoUrl\":").append(videoUrl != null ? "\"" + escapeJson(videoUrl) + "\"" : "null").append(",");
            sb.append("\"totalFrames\":").append(job.getTotalFrames()).append(",");
            sb.append("\"status\":\"").append(job.getStatus()).append("\",");
            sb.append("\"progress\":").append(String.format(Locale.US, "%.1f", job.getProgressPercentage())).append(",");
            sb.append("\"completedTasks\":").append(job.getCompletedTaskCount()).append(",");
            sb.append("\"totalTasks\":").append(job.getSubTaskCount()).append(",");
            sb.append("\"submissionTime\":").append(job.getSubmissionTimestamp()).append(",");
            sb.append("\"subTasks\":[");
            int tCount = 0;
            for (Job.SubTask st : job.getSubTasks()) {
                if (tCount++ > 0) sb.append(",");
                sb.append("{");
                sb.append("\"taskId\":\"").append(escapeJson(st.getTaskId())).append("\",");
                sb.append("\"range\":\"").append(escapeJson(st.getFrameRange())).append("\",");
                sb.append("\"status\":\"").append(st.getStatus()).append("\",");
                sb.append("\"worker\":").append(st.getAssignedWorkerId() != null ? "\"" + escapeJson(st.getAssignedWorkerId()) + "\"" : "null").append(",");
                sb.append("\"retries\":").append(st.getRetryCount());
                sb.append("}");
            }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ========================================================================
    // HTTP HANDLERS & CORS
    // ========================================================================

    private boolean isLocalRequest(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private class ClusterStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLocalRequest(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            sendJsonResponse(exchange, 200, generateTelemetrySnapshotJson());
        }
    }

    private class JobsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLocalRequest(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            sendJsonResponse(exchange, 200, generateJobsJson());
        }
    }

    private class SubmitJobHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLocalRequest(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String jobId = "JOB_" + System.currentTimeMillis();
            String workloadType = extractJsonString(body, "workloadType", "BLENDER");
            String blendFilePath = extractJsonString(body, "blendFilePath", "");
            String blendFileName = extractJsonString(body, "blendFileName", blendFilePath.isEmpty() ? "scene.blend" : new File(blendFilePath).getName());
            int totalFrames = extractJsonInt(body, "totalFrames", 50);
            int framesPerTask = extractJsonInt(body, "framesPerTask", 0);
            boolean cleanUpFrames = body.contains("\"cleanUpFrames\":true") || body.contains("\"deleteFramesAfterStitch\":true");
            String renderEngine = extractJsonString(body, "renderEngine", "CYCLES");

            byte[] blendFileBytes = null;

            // 1. Process uploaded blend file base64 data if present
            if (body.contains("\"blendFileBase64\":\"")) {
                String b64 = extractJsonString(body, "blendFileBase64", "");
                if (!b64.isEmpty()) {
                    try {
                        blendFileBytes = java.util.Base64.getDecoder().decode(b64);
                        File uploadDir = new File("./uploads");
                        if (!uploadDir.exists()) uploadDir.mkdirs();
                        File dest = new File(uploadDir, jobId + "_" + blendFileName);
                        java.nio.file.Files.write(dest.toPath(), blendFileBytes);
                        blendFilePath = dest.getAbsolutePath();
                        System.out.printf("[DASHBOARD] Saved uploaded blend file (%d bytes) to: %s\n", 
                            blendFileBytes.length, dest.getAbsolutePath());
                    } catch (Exception e) {
                        System.err.println("[DASHBOARD-ERR] Failed saving uploaded blend file: " + e.getMessage());
                    }
                }
            } else if (!blendFilePath.isEmpty() && new File(blendFilePath).exists()) {
                try {
                    blendFileBytes = java.nio.file.Files.readAllBytes(new File(blendFilePath).toPath());
                } catch (Exception ignored) {}
            }

            // 2. Auto-balance frames per task across available nodes if requested or not specified
            if (framesPerTask <= 0) {
                int availableNodes = Math.max(1, workerRegistry.getAvailableWorkers().size());
                framesPerTask = (int) Math.ceil((double) totalFrames / availableNodes);
                System.out.printf("[DASHBOARD] Auto-balanced workload: %d frames across %d node(s) -> %d frames/task\n",
                    totalFrames, availableNodes, framesPerTask);
            }

            // 3. Unique Sequenced default Job Name if not custom specified
            String customJobName = extractJsonString(body, "jobName", "").trim();
            String jobName = customJobName.isEmpty() 
                ? String.format("Render Workload #%d (%s)", jobSeq.getAndIncrement(), blendFileName)
                : customJobName;

            Map<String, Object> params = new HashMap<>();
            params.put("blendFilePath", blendFilePath);
            params.put("blendFileName", blendFileName);
            params.put("deleteFramesAfterStitch", cleanUpFrames);
            params.put("renderEngine", renderEngine);
            if (blendFileBytes != null) {
                params.put("blendFileBytes", blendFileBytes);
            }

            Job job = new Job(jobId, jobName, workloadType, totalFrames, params);
            jobManager.submitJob(job, framesPerTask);

            String response = String.format("{\"success\":true,\"jobId\":\"%s\",\"jobName\":\"%s\",\"subTasks\":%d,\"framesPerTask\":%d}",
                jobId, escapeJson(jobName), job.getSubTaskCount(), framesPerTask);
            sendJsonResponse(exchange, 201, response);
        }
    }

    private class OutputFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String uriPath = exchange.getRequestURI().getPath(); // e.g. /output/JOB_123/JOB_123_animation.mp4
            File file = new File("." + uriPath);
            if (file.exists() && file.isFile()) {
                String mime = uriPath.endsWith(".mp4") ? "video/mp4" 
                    : (uriPath.endsWith(".png") ? "image/png" : "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Type", mime);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, file.length());
                try (OutputStream os = exchange.getResponseBody(); InputStream is = new FileInputStream(file)) {
                    is.transferTo(os);
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Output file not found\"}");
            }
        }
    }

    private class CancelJobHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLocalRequest(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String jobId = extractJsonString(body, "jobId", "");

            if (jobId.isEmpty() && exchange.getRequestURI().getQuery() != null) {
                for (String param : exchange.getRequestURI().getQuery().split("&")) {
                    if (param.startsWith("jobId=")) jobId = param.substring(6);
                }
            }

            if (!jobId.isEmpty()) {
                jobManager.cancelJob(jobId, workerRegistry);
                sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Job cancelled\"}");
            } else {
                sendJsonResponse(exchange, 400, "{\"error\":\"Missing jobId parameter\"}");
            }
        }
    }

    private class InstallBlenderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String targetWorkerId = extractJsonString(body, "workerId", "");

            int sentCount = 0;

            for (WorkerState w : workerRegistry.getAllWorkers()) {
                if (targetWorkerId.isEmpty() || w.getWorkerId().equalsIgnoreCase(targetWorkerId)) {
                    try {
                        String osName = (w.getOsName() != null) ? w.getOsName().toLowerCase() : "";
                        String osType = "linux";
                        if (osName.contains("win")) {
                            osType = "windows";
                        } else if (osName.contains("mac")) {
                            osType = "macos";
                        }

                        // Determine Master IP relative to the connected worker
                        String masterIp = w.getSocket().getLocalAddress().getHostAddress();
                        String downloadUrl = "http://" + masterIp + ":" + httpPort + "/download/blender?os=" + osType;

                        GridMessage installMsg = new GridMessage(MessageType.INSTALL_BLENDER, "MASTER", downloadUrl);

                        ObjectOutputStream out = w.getOutStream();
                        if (out != null) {
                            synchronized (out) {
                                out.writeObject(installMsg);
                                out.flush();
                                out.reset();
                            }
                            sentCount++;
                            w.setInstallProgress(1.0); // Mark installation initiated
                        }
                    } catch (Exception e) {
                        System.err.printf("[DASHBOARD-ERR] Failed sending install command to worker %s: %s\n",
                            w.getWorkerId(), e.getMessage());
                    }
                }
            }

            String resp = String.format("{\"success\":true,\"message\":\"Installation triggered on %d worker(s)\",\"workersContacted\":%d}",
                sentCount, sentCount);
            sendJsonResponse(exchange, 200, resp);
        }
    }

    private class AgentJarDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            File jarFile = new File("agent.jar");
            if (jarFile.exists() && jarFile.isFile()) {
                exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"agent.jar\"");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, jarFile.length());
                try (OutputStream os = exchange.getResponseBody(); InputStream is = new FileInputStream(jarFile)) {
                    is.transferTo(os);
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Agent JAR file not found on Master Node. Make sure agent.jar exists in the root directory.\"}");
            }
        }
    }

    private class StaticWebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLocalRequest(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden: Dashboard access is restricted to localhost for security.\"}");
                return;
            }

            File htmlFile = new File("master-node/web/index.html");
            if (!htmlFile.exists()) {
                htmlFile = new File("web/index.html");
            }

            if (htmlFile.exists()) {
                byte[] htmlBytes = java.nio.file.Files.readAllBytes(htmlFile.toPath());
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, htmlBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
            } else {
                String fallback = "<html><body><h1>CampusGrid Dashboard</h1><p>Running on port " + httpPort + "</p></body></html>";
                byte[] bytes = fallback.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        }
    }

    private static String extractJsonString(String json, String key, String defaultVal) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx != -1) {
            int start = idx + pattern.length();
            int end = json.indexOf("\"", start);
            if (end != -1) return json.substring(start, end);
        }
        return defaultVal;
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx != -1) {
            int start = idx + pattern.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
                end++;
            }
            try {
                return Integer.parseInt(json.substring(start, end).trim());
            } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    // ========================================================================
    // LIGHTWEIGHT RFC 6455 WEBSOCKET BROADCASTER
    // ========================================================================

    public static class WebSocketBroadcaster {
        private final int port;
        private final java.util.function.Supplier<String> dataSupplier;
        private final Set<Socket> activeClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private ServerSocket serverSocket;
        private volatile boolean running = false;

        public WebSocketBroadcaster(int port, java.util.function.Supplier<String> dataSupplier) {
            this.port = port;
            this.dataSupplier = dataSupplier;
        }

        public void start() throws IOException {
            serverSocket = new ServerSocket(port);
            running = true;

            // Connection Accept Loop
            Thread acceptThread = new Thread(() -> {
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        new Thread(() -> handleClient(socket), "WebSocket-Client").start();
                    } catch (Exception ignored) {}
                }
            }, "WebSocket-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            // Periodic Telemetry Push Broadcast (every 2 seconds)
            Thread broadcastThread = new Thread(() -> {
                while (running) {
                    try {
                        Thread.sleep(2000);
                        if (!activeClients.isEmpty()) {
                            String json = dataSupplier.get();
                            broadcast(json);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {}
                }
            }, "WebSocket-Broadcast");
            broadcastThread.setDaemon(true);
            broadcastThread.start();
        }

        private void handleClient(Socket socket) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line == null) return;

                String key = null;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                        key = line.substring(18).trim();
                    }
                }

                if (key != null) {
                    // Complete WebSocket Handshake (RFC 6455)
                    String acceptKey = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8))
                    );

                    OutputStream out = socket.getOutputStream();
                    String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                                     "Upgrade: websocket\r\n" +
                                     "Connection: Upgrade\r\n" +
                                     "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    activeClients.add(socket);
                    System.out.println("[WEBSOCKET] Client connected: " + socket.getRemoteSocketAddress());

                    // Send initial snapshot immediately
                    sendFrame(socket, dataSupplier.get());

                    // Keep socket open
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[256];
                    while (running && in.read(buf) != -1) {}
                }
            } catch (Exception e) {
                // Client disconnect
            } finally {
                activeClients.remove(socket);
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        public void broadcast(String message) {
            for (Socket socket : activeClients) {
                try {
                    sendFrame(socket, message);
                } catch (Exception e) {
                    activeClients.remove(socket);
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }

        private synchronized void sendFrame(Socket socket, String text) throws IOException {
            byte[] rawData = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81); // Text frame opcode + FIN bit

            if (rawData.length <= 125) {
                frame.write(rawData.length);
            } else if (rawData.length <= 65535) {
                frame.write(126);
                frame.write((rawData.length >> 8) & 0xFF);
                frame.write(rawData.length & 0xFF);
            } else {
                frame.write(127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) ((rawData.length >> (8 * i)) & 0xFF));
                }
            }
            frame.write(rawData);

            OutputStream out = socket.getOutputStream();
            out.write(frame.toByteArray());
            out.flush();
        }

        public void stop() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
                for (Socket s : activeClients) s.close();
            } catch (Exception ignored) {}
            activeClients.clear();
        }
    }

    private class ToggleAssignmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isLocalRequest(exchange)) {
                sendJsonResponse(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String workerId = extractJsonString(body, "workerId", "").trim();
            String enabledStr = extractJsonString(body, "enabled", "true").trim();
            boolean enabled = Boolean.parseBoolean(enabledStr);
            
            WorkerState w = null;
            for (WorkerState ws : workerRegistry.getAllWorkers()) {
                if (ws.getWorkerId().equalsIgnoreCase(workerId)) {
                    w = ws;
                    break;
                }
            }
            
            if (w != null) {
                w.setTaskAssignmentEnabled(enabled);
                System.out.printf("[DASHBOARD] Worker [%s] task assignment toggled to: %b\n", workerId, enabled);
                sendJsonResponse(exchange, 200, "{\"success\":true}");
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Worker not found\"}");
            }
        }
    }

    private class BlenderDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            String os = params.getOrDefault("os", "").toLowerCase();
            
            File archiveFile = null;
            String contentType = "application/octet-stream";
            String filename = "blender-archive";
            
            if ("linux".equals(os)) {
                archiveFile = findArchiveFile("blender_archives/linux");
                contentType = "application/x-xz";
            } else if ("windows".equals(os)) {
                archiveFile = findArchiveFile("blender_archives/windows");
                contentType = "application/zip";
            } else if ("macos".equals(os)) {
                String arch = params.getOrDefault("arch", "").toLowerCase();
                if (arch.contains("arm") || arch.contains("aarch64")) {
                    archiveFile = new File("blender_archives/macos/blender-4.2.0-macos-arm64.dmg");
                } else if (arch.contains("x64") || arch.contains("amd64") || arch.contains("intel")) {
                    archiveFile = new File("blender_archives/macos/blender-4.2.0-macos-x64.dmg");
                }
                if (archiveFile == null || !archiveFile.exists()) {
                    archiveFile = findArchiveFile("blender_archives/macos");
                }
                contentType = "application/octet-stream";
            }
            
            if (archiveFile != null && archiveFile.exists() && archiveFile.isFile()) {
                filename = archiveFile.getName();
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, archiveFile.length());
                try (OutputStream osStream = exchange.getResponseBody(); InputStream is = new FileInputStream(archiveFile)) {
                    is.transferTo(osStream);
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Blender archive file not found for OS: " + os + "\"}");
            }
        }
        
        private File findArchiveFile(String dirPath) {
            File dir = new File(dirPath);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && !f.getName().startsWith(".")) {
                            return f;
                        }
                    }
                }
            }
            return null;
        }

        private Map<String, String> parseQueryParams(String query) {
            Map<String, String> result = new HashMap<>();
            if (query != null && !query.isEmpty()) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=", 2);
                    if (pair.length > 0) {
                        String key = pair[0];
                        String value = pair.length > 1 ? pair[1] : "";
                        result.put(key, value);
                    }
                }
            }
            return result;
        }
    }
}
