import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
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
    private final BenchmarkManager benchmarkManager;
    private final AgentVersionManager versionManager;
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
        this(jobManager, workerRegistry, new BenchmarkManager(workerRegistry), new AgentVersionManager(), httpPort, wsPort);
    }

    public DashboardServer(JobManager jobManager, WorkerRegistry workerRegistry, BenchmarkManager benchmarkManager, int httpPort, int wsPort) {
        this(jobManager, workerRegistry, benchmarkManager, new AgentVersionManager(), httpPort, wsPort);
    }

    public DashboardServer(JobManager jobManager, WorkerRegistry workerRegistry, BenchmarkManager benchmarkManager, AgentVersionManager versionManager, int httpPort, int wsPort) {
        this.jobManager = jobManager;
        this.workerRegistry = workerRegistry;
        this.benchmarkManager = benchmarkManager != null ? benchmarkManager : new BenchmarkManager(workerRegistry);
        this.versionManager = versionManager != null ? versionManager : new AgentVersionManager();
        this.jobManager.setBenchmarkManager(this.benchmarkManager);
        this.httpPort = httpPort;
        this.wsPort = wsPort;
    }

    public AgentVersionManager getVersionManager() {
        return versionManager;
    }

    /**
     * Starts the HTTP REST server and WebSocket broadcaster daemon.
     */
    public synchronized void start() throws IOException {
        // 1. Initialize HTTP REST Server & Web UI
        httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
        httpServer.createContext("/api/cluster/status", new ClusterStatusHandler());
        httpServer.createContext("/api/jobs", new JobsHandler());
        httpServer.createContext("/api/jobs/frames", new JobFramesHandler());
        httpServer.createContext("/api/jobs/submit", new SubmitJobHandler());
        httpServer.createContext("/api/jobs/cancel", new CancelJobHandler());
        httpServer.createContext("/api/nodes/install-blender", new InstallBlenderHandler());
        httpServer.createContext("/api/nodes/toggle-assignment", new ToggleAssignmentHandler());
        httpServer.createContext("/api/nodes/toggle-gpu", new ToggleGpuHandler());
        httpServer.createContext("/api/agent/version", new AgentVersionHandler());
        httpServer.createContext("/api/agent/compile-sync", new CompileAndSyncAgentHandler());
        httpServer.createContext("/api/benchmarks/comparison", new BenchmarkComparisonHandler());
        httpServer.createContext("/api/benchmarks/history", new BenchmarkHistoryHandler());
        httpServer.createContext("/download/agent.jar", new AgentJarDownloadHandler());
        httpServer.createContext("/download/agent.bat", new AgentBatDownloadHandler());
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
        sb.append("\"masterAgentVersion\":\"").append(escapeJson(versionManager.getCurrentVersion())).append("\",");
        sb.append("\"masterAgentBuild\":").append(versionManager.getCurrentBuild()).append(",");
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
            sb.append("\"agentVersion\":\"").append(escapeJson(w.getAgentVersion())).append("\",");
            sb.append("\"agentBuild\":").append(w.getAgentBuildNumber()).append(",");
            sb.append("\"isOutdated\":").append(versionManager.isAgentOutdated(w.getAgentVersion(), w.getAgentBuildNumber())).append(",");
            sb.append("\"status\":\"").append(w.getStatus()).append("\",");
            sb.append("\"osName\":\"").append(escapeJson(w.getOsName())).append("\",");
            sb.append("\"cpuModel\":\"").append(escapeJson(w.getCpuModel())).append("\",");
            sb.append("\"cpuArch\":\"").append(escapeJson(w.getCpuArch())).append("\",");
            sb.append("\"gpuModel\":\"").append(escapeJson(w.getGpuModel())).append("\",");
            sb.append("\"gpuComputeType\":\"").append(escapeJson(w.getGpuComputeType())).append("\",");
            sb.append("\"gpuAvailable\":").append(w.isGpuAvailable()).append(",");
            sb.append("\"useGpu\":").append(w.isUseGpu()).append(",");
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
                if (!defaultVideo.exists()) defaultVideo = new File("../output/" + job.getJobId() + "/" + job.getJobId() + "_animation.mp4");
                if (defaultVideo.exists()) {
                    videoUrl = "/output/" + job.getJobId() + "/" + job.getJobId() + "_animation.mp4";
                }
            }

            // Check rendered frame images
            int frameCount = 0;
            String previewFrameUrl = null;
            File[] outDirs = { new File("./output/" + job.getJobId()), new File("../output/" + job.getJobId()) };
            for (File d : outDirs) {
                if (d.exists() && d.isDirectory()) {
                    File[] files = d.listFiles();
                    if (files != null) {
                        List<File> imageFiles = new ArrayList<>();
                        for (File f : files) {
                            if (f.isFile() && f.length() > 0) {
                                String fn = f.getName().toLowerCase();
                                if (fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg")
                                        || fn.endsWith(".webp") || fn.endsWith(".bmp")) {
                                    imageFiles.add(f);
                                }
                            }
                        }
                        frameCount = imageFiles.size();
                        if (!imageFiles.isEmpty()) {
                            imageFiles.sort((f1, f2) -> {
                                int n1 = extractFrameNumber(f1.getName());
                                int n2 = extractFrameNumber(f2.getName());
                                return Integer.compare(n1, n2);
                            });
                            previewFrameUrl = "/output/" + job.getJobId() + "/" + imageFiles.get(0).getName();
                        }
                    }
                    break;
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
            sb.append("\"renderedFramesCount\":").append(frameCount).append(",");
            sb.append("\"hasFrames\":").append(frameCount > 0).append(",");
            sb.append("\"previewFrameUrl\":").append(previewFrameUrl != null ? "\"" + escapeJson(previewFrameUrl) + "\"" : "null").append(",");
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

    private static int extractFrameNumber(String name) {
        Pattern p = Pattern.compile("(?i)(?:frame_?)?(\\d+)");
        Matcher m = p.matcher(name);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ========================================================================
    // HTTP HANDLERS & CORS
    // ========================================================================

    private boolean isLocalRequest(HttpExchange exchange) {
        InetAddress addr = exchange.getRemoteAddress().getAddress();
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
            return true;
        }
        try {
            return NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception e) {
            return false;
        }
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
            int totalFrames = Math.max(1, extractJsonInt(body, "totalFrames", 50));
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
                        String safeBlendFileName = blendFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
                        File dest = new File(uploadDir, jobId + "_" + safeBlendFileName);
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

            // 2. Unique Sequenced default Job Name if not custom specified
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

            boolean isAutoBalance = body.contains("\"autoBalance\":true") || framesPerTask <= 0;
            if (isAutoBalance) {
                List<WorkerState> activeWorkers = workerRegistry.getAvailableWorkers();
                jobManager.submitJobWithWorkers(job, activeWorkers);
            } else {
                jobManager.submitJob(job, framesPerTask);
            }

            String response = String.format("{\"success\":true,\"jobId\":\"%s\",\"jobName\":\"%s\",\"subTasks\":%d,\"framesPerTask\":%d}",
                jobId, escapeJson(jobName), job.getSubTaskCount(), framesPerTask);
            sendJsonResponse(exchange, 201, response);
        }
    }

    private class OutputFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }

            String uriPath = exchange.getRequestURI().getPath();

            // Prevent path traversal attacks
            if (uriPath.contains("..") || uriPath.contains("\\")) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid path traversal characters in request\"}");
                return;
            }

            File file = new File("." + uriPath);
            if (!file.exists()) {
                file = new File(".." + uriPath);
            }

            try {
                File canonicalFile = file.getCanonicalFile();
                File baseDir1 = new File("./output").getCanonicalFile();
                File baseDir2 = new File("../output").getCanonicalFile();

                if (!canonicalFile.getPath().startsWith(baseDir1.getPath()) && !canonicalFile.getPath().startsWith(baseDir2.getPath())) {
                    sendJsonResponse(exchange, 403, "{\"error\":\"Access denied: Path outside output root\"}");
                    return;
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid file path\"}");
                return;
            }

            if (file.exists() && file.isFile()) {
                String lower = uriPath.toLowerCase();
                String mime = lower.endsWith(".mp4") ? "video/mp4" 
                    : (lower.endsWith(".png") ? "image/png"
                    : (lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                    : (lower.endsWith(".webp") ? "image/webp"
                    : (lower.endsWith(".bmp") ? "image/bmp"
                    : (lower.endsWith(".exr") ? "image/x-exr" : "application/octet-stream")))));
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

    private class JobFramesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            String jobId = params.getOrDefault("jobId", "").trim();
            
            if (jobId.isEmpty() || !jobId.matches("^[a-zA-Z0-9_.-]+$")) {
                sendJsonResponse(exchange, 400, "{\"error\":\"Invalid or missing jobId parameter\"}");
                return;
            }

            File[] searchDirs = {
                new File("./output/" + jobId),
                new File("../output/" + jobId)
            };

            List<String> frameUrls = new ArrayList<>();
            File foundDir = null;
            for (File dir : searchDirs) {
                if (dir.exists() && dir.isDirectory()) {
                    foundDir = dir;
                    break;
                }
            }

            if (foundDir != null) {
                File[] files = foundDir.listFiles();
                if (files != null) {
                    List<File> imageFiles = new ArrayList<>();
                    for (File f : files) {
                        if (f.isFile() && f.length() > 0) {
                            String name = f.getName().toLowerCase();
                            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                    || name.endsWith(".webp") || name.endsWith(".bmp")) {
                                imageFiles.add(f);
                            }
                        }
                    }
                    
                    // Sort numerically
                    imageFiles.sort((f1, f2) -> {
                        int n1 = extractFrameNumber(f1.getName());
                        int n2 = extractFrameNumber(f2.getName());
                        return Integer.compare(n1, n2);
                    });

                    for (File f : imageFiles) {
                        frameUrls.add("/output/" + jobId + "/" + f.getName());
                    }
                }
            }

            Job job = jobManager.getJob(jobId);
            String videoUrl = (job != null) ? job.getCompiledVideoUrl() : null;
            if (videoUrl == null) {
                File videoFile = new File("./output/" + jobId + "/" + jobId + "_animation.mp4");
                if (!videoFile.exists()) videoFile = new File("../output/" + jobId + "/" + jobId + "_animation.mp4");
                if (videoFile.exists()) videoUrl = "/output/" + jobId + "/" + jobId + "_animation.mp4";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"success\":true,");
            sb.append("\"jobId\":\"").append(escapeJson(jobId)).append("\",");
            sb.append("\"totalFrames\":").append(frameUrls.size()).append(",");
            sb.append("\"hasVideo\":").append(videoUrl != null && !videoUrl.isEmpty()).append(",");
            sb.append("\"videoUrl\":").append(videoUrl != null ? "\"" + escapeJson(videoUrl) + "\"" : "null").append(",");
            sb.append("\"frames\":[");
            for (int i = 0; i < frameUrls.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(frameUrls.get(i))).append("\"");
            }
            sb.append("]}");

            sendJsonResponse(exchange, 200, sb.toString());
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
                        String arch = "x64";
                        if (osName.contains("win")) {
                            osType = "windows";
                        } else if (osName.contains("mac")) {
                            osType = "macos";
                            if (osName.contains("arm") || osName.contains("aarch64") || System.getProperty("os.arch").contains("aarch64")) {
                                arch = "arm64";
                            }
                        }

                        // Determine Master IP relative to the connected worker
                        String masterIp = getReachableMasterIp(w, exchange);
                        String downloadUrl = "http://" + masterIp + ":" + httpPort + "/download/blender?os=" + osType + "&arch=" + arch;

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

    private String getReachableMasterIp(WorkerState w, HttpExchange exchange) {
        // 1. Try exchange Host header if available
        if (exchange != null && exchange.getRequestHeaders().containsKey("Host")) {
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            if (hostHeader != null && !hostHeader.isEmpty()) {
                String host = hostHeader.split(":")[0].trim();
                if (!host.isEmpty() && !host.equals("0.0.0.0") && !host.equals("localhost") && !host.equals("127.0.0.1")) {
                    return host;
                }
            }
        }

        // 2. Check worker socket local address
        if (w != null && w.getSocket() != null && w.getSocket().getLocalAddress() != null) {
            String sockIp = w.getSocket().getLocalAddress().getHostAddress();
            if (sockIp != null && !sockIp.equals("0.0.0.0") && !sockIp.equals("0:0:0:0:0:0:0:0") && !sockIp.equals("127.0.0.1")) {
                return sockIp;
            }
        }

        // 3. Fallback: Query system network interfaces for first active non-loopback IPv4
        try {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
                String host = socket.getLocalAddress().getHostAddress();
                if (host != null && !host.equals("0.0.0.0") && !host.equals("127.0.0.1")) {
                    return host;
                }
            }
        } catch (Exception ignored) {}

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

    private class AgentVersionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            String json = String.format(Locale.US,
                "{\"version\":\"%s\",\"build\":%d,\"timestamp\":%d,\"downloadUrl\":\"/download/agent.jar\"}",
                escapeJson(versionManager.getCurrentVersion()),
                versionManager.getCurrentBuild(),
                versionManager.getLastUpdatedTimestamp()
            );
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class CompileAndSyncAgentHandler implements HttpHandler {
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
            
            // 1. Increment version & re-package agent.jar
            String newVer = versionManager.incrementAndPackageAgent();
            int newBuild = versionManager.getCurrentBuild();
            
            // 2. Broadcast UPDATE_AGENT to all connected worker nodes
            int notified = 0;
            for (WorkerState w : workerRegistry.getAllWorkers()) {
                try {
                    ObjectOutputStream out = w.getOutStream();
                    if (out != null) {
                        synchronized (out) {
                            out.writeObject(new GridMessage(MessageType.UPDATE_AGENT, "MASTER", "/download/agent.jar"));
                            out.writeObject("UPDATE_AGENT: /download/agent.jar | VERSION: " + newVer + " | BUILD: " + newBuild);
                            out.flush();
                            out.reset();
                        }
                        notified++;
                        System.out.printf("[AUTO-SYNC] Dispatched UPDATE_AGENT directive to Worker [%s]\n", w.getWorkerId());
                    }
                } catch (Exception e) {
                    System.err.printf("[AUTO-SYNC-ERR] Failed notifying worker %s of agent update: %s\n", w.getWorkerId(), e.getMessage());
                }
            }

            String json = String.format(Locale.US,
                "{\"success\":true,\"version\":\"%s\",\"build\":%d,\"notifiedWorkers\":%d}",
                escapeJson(newVer), newBuild, notified
            );
            sendJsonResponse(exchange, 200, json);
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
            if (!jarFile.exists() || jarFile.length() < 512) {
                // Generate fresh agent.jar on demand
                versionManager.packageAgentJar(jarFile);
            }

            if (jarFile.exists() && jarFile.isFile()) {
                exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"agent.jar\"");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, jarFile.length());
                try (OutputStream os = exchange.getResponseBody(); InputStream is = new FileInputStream(jarFile)) {
                    is.transferTo(os);
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Agent JAR file could not be generated on Master Node.\"}");
            }
        }
    }

    private class AgentBatDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            File batFile = new File("agent.bat");
            if (!batFile.exists() || batFile.length() < 512) {
                File jarFile = new File("agent.jar");
                if (!jarFile.exists() || jarFile.length() < 512) {
                    versionManager.packageAgentJar(jarFile);
                }
                versionManager.generateStandaloneAgentBat(jarFile, batFile);
            }

            if (batFile.exists() && batFile.isFile()) {
                exchange.getResponseHeaders().set("Content-Type", "application/x-bat");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"agent.bat\"");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, batFile.length());
                try (OutputStream os = exchange.getResponseBody(); InputStream is = new FileInputStream(batFile)) {
                    is.transferTo(os);
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Agent BAT file could not be generated on Master Node.\"}");
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

    private class ToggleGpuHandler implements HttpHandler {
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
                w.setUseGpu(enabled);
                System.out.printf("[DASHBOARD] Worker [%s] GPU compute acceleration toggled to: %b\n", workerId, enabled);
                
                // Dispatch toggle command immediately to worker
                try {
                    ObjectOutputStream out = w.getOutStream();
                    if (out != null) {
                        synchronized (out) {
                            out.writeObject(new GridMessage(MessageType.TOGGLE_GPU, "MASTER", enabled));
                            out.writeObject("TOGGLE_GPU:" + enabled);
                            out.flush();
                            out.reset();
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("[DASHBOARD-ERR] Failed dispatching TOGGLE_GPU to worker %s: %s\n", workerId, e.getMessage());
                }

                sendJsonResponse(exchange, 200, "{\"success\":true,\"workerId\":\"" + escapeJson(workerId) + "\",\"useGpu\":" + enabled + "}");
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"Worker not found\"}");
            }
        }
    }

    private class BenchmarkComparisonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);
            String jobId = params.get("jobId");
            String json = benchmarkManager.generateComparisonJson(jobId);
            sendJsonResponse(exchange, 200, json);
        }
    }

    private class BenchmarkHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 204, "");
                return;
            }
            String json = benchmarkManager.generateComparisonJson(null);
            sendJsonResponse(exchange, 200, json);
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
            String arch = params.getOrDefault("arch", "").toLowerCase();
            
            File archiveFile = null;
            String contentType = "application/octet-stream";
            String filename = "blender-archive";
            
            if ("linux".equals(os)) {
                archiveFile = findArchiveFile("blender_archives/linux", "blender-4.2.0-linux-x64.tar.xz");
                contentType = "application/x-xz";
            } else if ("windows".equals(os)) {
                archiveFile = findArchiveFile("blender_archives/windows", "blender-4.2.0-windows-x64.zip");
                contentType = "application/zip";
            } else if ("macos".equals(os)) {
                if (arch.contains("arm") || arch.contains("aarch64")) {
                    archiveFile = findArchiveFile("blender_archives/macos", "blender-4.2.0-macos-arm64.dmg");
                } else if (arch.contains("x64") || arch.contains("amd64") || arch.contains("intel")) {
                    archiveFile = findArchiveFile("blender_archives/macos", "blender-4.2.0-macos-x64.dmg");
                }
                if (archiveFile == null || !archiveFile.exists()) {
                    archiveFile = findArchiveFile("blender_archives/macos", null);
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
                System.out.println("[DASHBOARD] Served Blender archive: " + filename + " (" + archiveFile.length() + " bytes)");
            } else {
                System.err.println("[DASHBOARD-ERR] Archive not found for os=" + os + " arch=" + arch);
                sendJsonResponse(exchange, 404, "{\"error\":\"Blender archive file not found for OS: " + os + "\"}");
            }
        }
        
        private File findArchiveFile(String dirPath, String preferredFileName) {
            String[] basePrefixes = { ".", "..", "./master-node", "../master-node" };
            
            if (preferredFileName != null && !preferredFileName.isEmpty()) {
                for (String prefix : basePrefixes) {
                    File candidate = new File(prefix + File.separator + dirPath + File.separator + preferredFileName);
                    if (candidate.exists() && candidate.isFile() && candidate.length() > 0) {
                        return candidate;
                    }
                }
            }

            for (String prefix : basePrefixes) {
                File dir = new File(prefix + File.separator + dirPath);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && !f.getName().startsWith(".") && f.length() > 0) {
                                return f;
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    private static Map<String, String> parseQueryParams(String query) {
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
