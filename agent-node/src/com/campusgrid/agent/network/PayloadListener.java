package com.campusgrid.agent.network;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import com.campusgrid.core.*;

/**
 * Listens for compute tasks (payloads) sent by the Master node over the socket connection.
 * Runs in its own background thread, deserializes incoming objects, executes tasks,
 * and writes the return results back to the Master node.
 * Reuses streams managed by MasterConnection.
 */
public class PayloadListener implements Runnable {

    private final MasterConnection connection;
    private volatile boolean running = false;
    private Thread thread;

    // Asynchronous rendering task tracking
    private Thread currentRenderThread = null;
    private String currentJobId = null;

    // GPU Compute acceleration preference (can be toggled live by Master)
    public static volatile boolean useGpu = com.campusgrid.agent.os.HardwareCollector.isGpuAvailable();

    /**
     * Constructs a PayloadListener associated with the given MasterConnection.
     *
     * @param connection the MasterConnection containing the active socket and streams.
     */
    public PayloadListener(MasterConnection connection) {
        this.connection = connection;
    }

    /**
     * Starts the listener background thread if not already running.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "PayloadListenerThread");
        thread.start();
        System.out.println("[TASK] Listener started");
    }

    /**
     * Stops the listener background thread gracefully, killing any active Blender rendering.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        
        // Terminate any active rendering process and thread
        if (currentJobId != null) {
            com.campusgrid.agent.blender.BlenderJobExecutor.cancelJob(currentJobId);
        }
        if (currentRenderThread != null) {
            currentRenderThread.interrupt();
            currentRenderThread = null;
            currentJobId = null;
        }

        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        System.out.println("[TASK] Listener stopped");
    }

    /**
     * Checks if the listener is currently running.
     *
     * @return true if the listener thread is active, false otherwise.
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        ObjectOutputStream oos = connection.getObjectOutputStream();
        ObjectInputStream ois = connection.getObjectInputStream();

        if (oos == null || ois == null) {
            System.out.println("[TASK] Connection lost.");
            stop();
            return;
        }

        // Report initial READY status
        try {
            com.campusgrid.agent.blender.ProgressReporter initialReporter = 
                new com.campusgrid.agent.blender.ProgressReporter(connection);
            String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
            initialReporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "READY", blenderVer, true);
        } catch (Exception e) {
            // Ignore initial status errors
        }

        while (running) {
            if (!connection.isConnected()) {
                System.out.println("[TASK] Connection lost.");
                stop();
                break;
            }

            try {
                System.out.println("[TASK] Waiting for task...");
                Object obj = ois.readObject();
                System.out.println("[TASK] Task received");

                if (obj instanceof GridTask) {
                    GridTask task = (GridTask) obj;
                    System.out.println("[TASK] Executing GridTask...");

                    // Report BUSY status during GridTask calculation
                    try {
                        com.campusgrid.agent.blender.ProgressReporter busyReporter = 
                            new com.campusgrid.agent.blender.ProgressReporter(connection);
                        String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
                        busyReporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "BUSY", blenderVer, true);
                    } catch (Exception e) {}

                    com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = true;
                    Object result = task.execute();
                    com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = false;

                    connection.sendObject(result);
                    System.out.println("[TASK] Result sent");

                    try {
                        com.campusgrid.agent.blender.ProgressReporter readyReporter = 
                            new com.campusgrid.agent.blender.ProgressReporter(connection);
                        String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
                        readyReporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "READY", blenderVer, true);
                    } catch (Exception e) {}
                } else if (obj instanceof GridMessage message) {
                    handleGridMessagePacket(message);
                } else if (obj instanceof TaskAssignmentPayload taskAssignment) {
                    handleTaskAssignmentPacket(taskAssignment);
                } else if (obj instanceof com.campusgrid.agent.blender.BlenderRenderTask) {
                    com.campusgrid.agent.blender.BlenderRenderTask task = (com.campusgrid.agent.blender.BlenderRenderTask) obj;
                    System.out.println("[TASK] Direct Blender render task received: " + task.getJobId());
                    handleAsyncRender(task, task.getJobId() + "_T001");
                } else if (obj instanceof com.campusgrid.agent.blender.BlenderJob) {
                    com.campusgrid.agent.blender.BlenderJob job = (com.campusgrid.agent.blender.BlenderJob) obj;
                    System.out.println("[TASK] Legacy Blender job received: " + job.getJobId());
                    com.campusgrid.agent.blender.BlenderRenderTask wrapped = new com.campusgrid.agent.blender.BlenderRenderTask(
                        job.getJobId(), job.getBlendFilePath(), job.getFrameStart(), job.getFrameEnd(), job.getOutputDir(), job.getRenderEngine()
                    );
                    handleAsyncRender(wrapped, job.getJobId() + "_T001");
                } else if (obj instanceof String rawString && rawString.startsWith("TOGGLE_GPU:")) {
                    boolean enabled = Boolean.parseBoolean(rawString.substring(11).trim());
                    useGpu = enabled;
                    System.out.println("[TASK] GPU acceleration preference set to: " + useGpu);
                } else if (obj instanceof String rawString && rawString.startsWith("UPDATE_AGENT:")) {
                    String payload = rawString.substring(13).trim();
                    String downloadUrl = "/download/agent.jar";
                    String targetVer = null;
                    int targetBuild = 0;
                    for (String part : payload.split("\\|")) {
                        part = part.trim();
                        if (part.startsWith("URL:")) {
                            downloadUrl = part.substring(4).trim();
                        } else if (part.startsWith("VERSION:")) {
                            targetVer = part.substring(8).trim();
                        } else if (part.startsWith("BUILD:")) {
                            try { targetBuild = Integer.parseInt(part.substring(6).trim()); } catch (Exception ignored) {}
                        } else if (!part.contains(":") && !part.isEmpty()) {
                            downloadUrl = part;
                        }
                    }
                    AgentUpdater.performAutoUpdate(downloadUrl, targetVer, targetBuild, connection.getMasterIp(), connection.getMasterPort());
                } else if (isKillCommand(obj)) {
                    String targetJobId = extractJobIdFromPacket(obj);
                    System.out.println("[TASK] Kill/Cancel command received for jobId: " + targetJobId);
                    cancelActiveRender(targetJobId);
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("[TASK] Connection lost: " + e.getMessage());
                connection.disconnect();
                break;
            }
        }
    }


    private void handleGridMessagePacket(GridMessage msgObj) {
        try {
            MessageType typeVal = msgObj.getType();
            Object payloadVal = msgObj.getPayload();

            if (typeVal == MessageType.SUBMIT_TASK && payloadVal instanceof TaskAssignmentPayload assignment) {
                handleTaskAssignmentPacket(assignment);
            } else if (typeVal == MessageType.CANCEL_TASK) {
                String cancelJobId = (payloadVal != null) ? payloadVal.toString() : null;
                cancelActiveRender(cancelJobId);
            } else if (typeVal == MessageType.TOGGLE_GPU) {
                boolean enabled = Boolean.parseBoolean(String.valueOf(payloadVal));
                useGpu = enabled;
                System.out.println("[TASK] GPU acceleration preference set to: " + useGpu);
            } else if (typeVal == MessageType.UPDATE_AGENT) {
                String downloadUrl = (payloadVal != null) ? payloadVal.toString() : "/download/agent.jar";
                AgentUpdater.performAutoUpdate(downloadUrl, null, 0, connection.getMasterIp(), connection.getMasterPort());
            } else if (typeVal == MessageType.INSTALL_BLENDER) {
                String downloadUrl = (payloadVal != null) ? payloadVal.toString() : "";
                
                // If downloadUrl contains 0.0.0.0 or loopback while master is remote, rewrite host
                if (downloadUrl != null && !downloadUrl.isEmpty() && connection.getMasterIp() != null) {
                    String masterIp = connection.getMasterIp();
                    if (!masterIp.equals("127.0.0.1") && !masterIp.equalsIgnoreCase("localhost")) {
                        downloadUrl = downloadUrl.replace("://0.0.0.0:", "://" + masterIp + ":")
                                                 .replace("://127.0.0.1:", "://" + masterIp + ":")
                                                 .replace("://localhost:", "://" + masterIp + ":");
                    }
                }

                // Ensure the Agent uses its authentic OS and Architecture in the download request
                String actualOs = com.campusgrid.agent.blender.BlenderUtils.getOsType();
                String actualArch = com.campusgrid.agent.blender.BlenderUtils.getSystemArch();
                if (downloadUrl != null && downloadUrl.contains("os=")) {
                    downloadUrl = downloadUrl.replaceAll("os=[a-zA-Z0-9]+", "os=" + actualOs);
                }
                if (downloadUrl != null && downloadUrl.contains("arch=")) {
                    downloadUrl = downloadUrl.replaceAll("arch=[a-zA-Z0-9]+", "arch=" + actualArch);
                }
                
                final String finalDownloadUrl = downloadUrl;
                System.out.println("[TASK] Received INSTALL_BLENDER command from Master. Starting installer from: " + finalDownloadUrl);
                new Thread(() -> {
                    com.campusgrid.agent.blender.BlenderInstaller.installBlender(finalDownloadUrl, (pct, msg) -> {
                        try {
                            String ver = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
                            connection.sendObject(String.format(
                                "HEARTBEAT | AGENT_VERSION: %s | AGENT_BUILD: %d | TEMP: %d°C | CPU: %.1f%% | RAM: %.1f%% | OS: %s | CPU_MODEL: %s | ARCH: %s | GPU: %s | GPUTYPE: %s | GPU_AVAIL: %b | USEGPU: %b | BLENDER: %s | INSTALL: %.1f | MSG: %s",
                                com.campusgrid.agent.Agent.CURRENT_VERSION,
                                com.campusgrid.agent.Agent.CURRENT_BUILD,
                                com.campusgrid.agent.os.LinuxTelemetry.getCpuTemperatureCelsius(),
                                com.campusgrid.agent.os.LinuxTelemetry.getCpuLoadPercent(),
                                com.campusgrid.agent.os.LinuxTelemetry.getRamUsagePercent(),
                                System.getProperty("os.name"),
                                com.campusgrid.agent.os.HardwareCollector.getCpuModelName(),
                                com.campusgrid.agent.os.HardwareCollector.getCpuArchitecture(),
                                com.campusgrid.agent.os.HardwareCollector.getGpuModelName(),
                                com.campusgrid.agent.os.HardwareCollector.getGpuComputeType(),
                                com.campusgrid.agent.os.HardwareCollector.isGpuAvailable(),
                                useGpu,
                                ver,
                                pct,
                                msg
                            ));
                        } catch (Exception ignored) {}
                    });
                }, "Blender-Background-Installer").start();
            }
        } catch (Exception e) {
            System.err.println("[TASK] Error handling GridMessage: " + e.getMessage());
        }
    }

    private void handleTaskAssignmentPacket(TaskAssignmentPayload taskAssignmentObj) {
        try {
            String jobId = taskAssignmentObj.getJobId();
            String taskId = taskAssignmentObj.getTaskId();
            String range = taskAssignmentObj.getAssignedFrameRange();
            Object taskData = taskAssignmentObj.getTaskData();

            int start = 1, end = 1;
            if (range != null && range.contains("-")) {
                String[] parts = range.split("-");
                start = Integer.parseInt(parts[0].trim());
                end = Integer.parseInt(parts[1].trim());
            } else if (range != null && !range.trim().isEmpty()) {
                start = end = Integer.parseInt(range.trim());
            }

            String blendPath = "test.blend";
            if (taskData instanceof String s && !s.trim().isEmpty()) {
                blendPath = s.trim();
            } else if (taskData instanceof byte[] bytes && bytes.length > 0) {
                // Use absolute path so snap-sandboxed Blender on Linux can find it
                java.io.File cacheDir = new java.io.File("./cache/" + jobId).getCanonicalFile();
                if (!cacheDir.exists()) cacheDir.mkdirs();
                java.io.File cachedFile = new java.io.File(cacheDir, "scene.blend");
                java.nio.file.Files.write(cachedFile.toPath(), bytes);
                blendPath = cachedFile.getAbsolutePath();
                System.out.printf("[TASK] Unpacked binary blend file (%d bytes) to: %s\n", bytes.length, blendPath);
            }

            System.out.printf("[TASK] Received Task [%s] for Job [%s] (Frames: %d-%d, Blend: %s)\n",
                taskId, jobId, start, end, blendPath);

            // Use absolute output path — required for snap-sandboxed Blender on Linux
            String absOutputDir = new java.io.File("./output/" + jobId).getCanonicalPath();
            
            String engine = taskAssignmentObj.getRenderEngine();
            if (engine == null || engine.trim().isEmpty()) {
                engine = "CYCLES";
            }
            
            com.campusgrid.agent.blender.BlenderRenderTask renderTask = new com.campusgrid.agent.blender.BlenderRenderTask(
                jobId, blendPath, start, end, absOutputDir, engine
            );
            handleAsyncRender(renderTask, taskId);
        } catch (Exception e) {
            System.err.println("[TASK] Failed to unpack TaskAssignment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private synchronized void cancelActiveRender(String targetJobId) {
        if (targetJobId == null || targetJobId.equals(currentJobId)) {
            if (currentJobId != null) {
                com.campusgrid.agent.blender.BlenderJobExecutor.cancelJob(currentJobId);
            }
            if (currentRenderThread != null && currentRenderThread.isAlive()) {
                currentRenderThread.interrupt();
            }
            System.out.println("[TASK] Render cancelled for Job: " + targetJobId);
        }
    }

    /**
     * Executes the Blender render task in a separate worker thread.
     * Prevents blocking of the main thread and heartbeat service.
     */
    private synchronized void handleAsyncRender(com.campusgrid.agent.blender.BlenderRenderTask task, String taskId) {
        // Cancel any active render first
        if (currentRenderThread != null && currentRenderThread.isAlive()) {
            System.out.println("[TASK] Interrupting running render to start new task: " + task.getJobId());
            if (currentJobId != null) {
                com.campusgrid.agent.blender.BlenderJobExecutor.cancelJob(currentJobId);
            }
            currentRenderThread.interrupt();
        }

        currentJobId = task.getJobId();
        currentRenderThread = new Thread(() -> {
            com.campusgrid.agent.blender.ProgressReporter reporter = 
                new com.campusgrid.agent.blender.ProgressReporter(connection);
            String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
            
            long startTime = System.currentTimeMillis();
            String status = "SUCCESS";
            String stateReport = "COMPLETED";
            java.util.List<String> rawFiles = null;
            java.util.List<String> renderedFiles = new java.util.ArrayList<>();
            
            // Immediate startup report so UI reflects active rendering instantly
            int totalFramesToRender = Math.max(1, task.getFrameEnd() - task.getFrameStart() + 1);
            reporter.reportStatus(task.getJobId(), task.getFrameStart(), totalFramesToRender, 0.0, -1.0, "RENDERING", blenderVer, true);

            try {
                rawFiles = com.campusgrid.agent.blender.BlenderJobExecutor.executeJob(
                    task.getJobId(),
                    task.getBlendFilePath(),
                    task.getFrameStart(),
                    task.getFrameEnd(),
                    task.getOutputDir(),
                    task.getRenderEngine(),
                    useGpu,
                    reporter
                );
            } catch (InterruptedException e) {
                status = "CANCELLED";
                stateReport = "CANCELLED";
                System.out.println("[TASK] Blender render cancelled: " + e.getMessage());
            } catch (Exception e) {
                status = "FAILED";
                stateReport = "FAILED";
                System.err.println("[TASK] Blender render failed: " + e.getMessage());
                e.printStackTrace();
            } finally {
                com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = false;
                renderedFiles = collectRenderedFiles(task.getJobId(), task.getOutputDir(), rawFiles);
                
                synchronized (PayloadListener.this) {
                    if (Thread.currentThread() == currentRenderThread) {
                        currentRenderThread = null;
                        currentJobId = null;
                    }
                }
            }

            // Report final state (COMPLETED, FAILED, or CANCELLED)
            int total = Math.max(1, task.getFrameEnd() - task.getFrameStart() + 1);
            int current = "COMPLETED".equals(stateReport) ? task.getFrameEnd() : task.getFrameStart();
            double pct = "COMPLETED".equals(stateReport) ? 100.0 : 0.0;
            reporter.reportStatus(task.getJobId(), current, total, pct, -1.0, stateReport, blenderVer, true);

            long duration = System.currentTimeMillis() - startTime;
            byte[] zippedBytes = zipFiles(renderedFiles);
            System.out.printf("[TASK] Packaged %d bytes of zipped frames (%d images) for Task [%s] in Job [%s]\n",
                zippedBytes.length, renderedFiles.size(), taskId, task.getJobId());

            com.campusgrid.agent.blender.RenderResult result = new com.campusgrid.agent.blender.RenderResult(
                task.getJobId(),
                taskId,
                reporter.getWorkerId(),
                renderedFiles,
                duration,
                status,
                zippedBytes
            );

            try {
                // Send RenderResult
                connection.sendObject(result);
                System.out.println("[TASK] Render result sent to Master: " + result);
            } catch (IOException e) {
                System.err.println("[TASK] Failed to send render result: " + e.getMessage());
            }

            // Restore READY status after execution
            try {
                reporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "READY", blenderVer, true);
            } catch (Exception ignored) {}
        }, "BlenderRenderThread");

        currentRenderThread.start();
    }

    /**
     * Determines whether the received payload represents a kill/cancel request.
     * Checks for specific cancel strings or TaskPacket reflection commands.
     *
     * @param obj the received object.
     * @return true if it is a cancel command, false otherwise.
     */
    private boolean isKillCommand(Object obj) {
        if (obj == null) {
            return false;
        }

        if (obj instanceof String) {
            String s = ((String) obj).toUpperCase();
            return s.equals("KILL") || s.equals("CANCEL") || s.equals("ABORT") 
                || s.contains("KILL") || s.contains("CANCEL");
        }

        String className = obj.getClass().getName();
        if (className.contains("TaskPacket")) {
            try {
                java.lang.reflect.Field cmdField = obj.getClass().getDeclaredField("command");
                cmdField.setAccessible(true);
                Object cmdVal = cmdField.get(obj);
                if (cmdVal instanceof String) {
                    String cmdStr = ((String) cmdVal).toUpperCase();
                    return cmdStr.equals("KILL") || cmdStr.equals("CANCEL");
                }
            } catch (Exception e) {
                // Ignore reflection errors
            }
        }

        return false;
    }

    /**
     * Extract the targeted jobId from the master cancellation packet if available.
     *
     * @param obj the received packet.
     * @return the targeted jobId, or null if generic/not present.
     */
    private String extractJobIdFromPacket(Object obj) {
        if (obj == null) {
            return null;
        }
        String className = obj.getClass().getName();
        if (className.contains("TaskPacket")) {
            try {
                java.lang.reflect.Field chunkField = obj.getClass().getDeclaredField("chunk");
                chunkField.setAccessible(true);
                Object chunkVal = chunkField.get(obj);
                if (chunkVal != null) {
                    java.lang.reflect.Field taskIdField = chunkVal.getClass().getDeclaredField("taskId");
                    taskIdField.setAccessible(true);
                    Object taskIdVal = taskIdField.get(chunkVal);
                    if (taskIdVal instanceof String) {
                        return (String) taskIdVal;
                    }
                }
            } catch (Exception e) {
                // Ignore reflection errors
            }
        }
        return null;
    }

    private java.util.List<String> collectRenderedFiles(String jobId, String outputDir, java.util.List<String> executorFiles) {
        java.util.LinkedHashSet<String> filesSet = new java.util.LinkedHashSet<>();
        
        // 1. Add files returned directly by Blender executor
        if (executorFiles != null) {
            for (String p : executorFiles) {
                if (p != null) {
                    java.io.File f = new java.io.File(p);
                    if (f.exists() && f.isFile() && f.length() > 0) {
                        filesSet.add(f.getAbsolutePath());
                    }
                }
            }
        }

        // 2. Search task outputDir, ./output/<jobId>, ../output/<jobId>, ./output
        java.util.List<java.io.File> searchDirs = new java.util.ArrayList<>();
        if (outputDir != null && !outputDir.trim().isEmpty()) {
            searchDirs.add(new java.io.File(outputDir.trim()));
        }
        searchDirs.add(new java.io.File("./output/" + jobId));
        searchDirs.add(new java.io.File("../output/" + jobId));
        searchDirs.add(new java.io.File("./output"));

        for (java.io.File dir : searchDirs) {
            if (dir.exists() && dir.isDirectory()) {
                java.io.File[] list = dir.listFiles();
                if (list != null) {
                    for (java.io.File f : list) {
                        if (f.isFile() && f.length() > 0) {
                            String name = f.getName().toLowerCase();
                            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                    || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".exr")
                                    || name.endsWith(".tga") || name.endsWith(".tif") || name.endsWith(".tiff")) {
                                filesSet.add(f.getAbsolutePath());
                            }
                        }
                    }
                }
            }
        }

        java.util.List<String> resultList = new java.util.ArrayList<>(filesSet);
        System.out.printf("[TASK] Collected %d rendered frames for Job [%s] to upload to Master.\n",
            resultList.size(), jobId);
        return resultList;
    }

    private byte[] zipFiles(java.util.List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (String filePath : filePaths) {
                java.io.File file = new java.io.File(filePath);
                if (file.exists() && file.isFile()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(file.getName());
                        zos.putNextEntry(entry);
                        byte[] buffer = new byte[4096];
                        int length;
                        while ((length = fis.read(buffer)) >= 0) {
                            zos.write(buffer, 0, length);
                        }
                        zos.closeEntry();
                    } catch (java.io.IOException e) {
                        System.err.println("[TASK-ERR] Failed to zip file " + filePath + ": " + e.getMessage());
                    }
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("[TASK-ERR] Failed creating zip output stream: " + e.getMessage());
        }
        return baos.toByteArray();
    }
}
