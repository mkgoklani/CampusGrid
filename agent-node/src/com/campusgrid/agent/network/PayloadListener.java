package com.campusgrid.agent.network;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import com.campusgrid.core.GridTask;

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
                } else if (isGridMessage(obj)) {
                    handleGridMessagePacket(obj);
                } else if (isTaskAssignment(obj)) {
                    handleTaskAssignmentPacket(obj);
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
                } else if (isKillCommand(obj)) {
                    String targetJobId = extractJobIdFromPacket(obj);
                    System.out.println("[TASK] Kill/Cancel command received for jobId: " + targetJobId);
                    cancelActiveRender(targetJobId);
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("[TASK] Connection lost.");
                stop();
                break;
            }
        }
    }


    private void handleGridMessagePacket(Object msgObj) {
        try {
            java.lang.reflect.Method getTypeMethod = msgObj.getClass().getMethod("getType");
            java.lang.reflect.Method getPayloadMethod = msgObj.getClass().getMethod("getPayload");
            Object typeVal = getTypeMethod.invoke(msgObj);
            Object payloadVal = getPayloadMethod.invoke(msgObj);

            String typeStr = (typeVal != null) ? typeVal.toString() : "";
            if ("SUBMIT_TASK".equalsIgnoreCase(typeStr) && payloadVal != null) {
                handleTaskAssignmentPacket(payloadVal);
            } else if ("CANCEL_TASK".equalsIgnoreCase(typeStr)) {
                String cancelJobId = (payloadVal != null) ? payloadVal.toString() : null;
                cancelActiveRender(cancelJobId);
            } else if ("INSTALL_BLENDER".equalsIgnoreCase(typeStr)) {
                System.out.println("[TASK] Received INSTALL_BLENDER command from Master. Starting installer...");
                new Thread(() -> {
                    com.campusgrid.agent.blender.BlenderInstaller.installBlender((pct, msg) -> {
                        try {
                            String ver = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
                            connection.sendObject(String.format(java.util.Locale.US,
                                "HEARTBEAT | TEMP: %d°C | CPU: %.1f%% | RAM: %.1f%% | OS: %s | BLENDER: %s | INSTALL: %.1f | MSG: %s",
                                com.campusgrid.agent.os.LinuxTelemetry.getCpuTemperatureCelsius(),
                                com.campusgrid.agent.os.LinuxTelemetry.getCpuLoadPercent(),
                                com.campusgrid.agent.os.LinuxTelemetry.getRamUsagePercent(),
                                System.getProperty("os.name"),
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

    private boolean isGridMessage(Object obj) {
        return obj != null && obj.getClass().getName().contains("GridMessage");
    }

    private boolean isTaskAssignment(Object obj) {
        return obj != null && obj.getClass().getName().contains("TaskAssignment");
    }

    private void handleTaskAssignmentPacket(Object taskAssignmentObj) {
        try {
            Class<?> clazz = taskAssignmentObj.getClass();
            String jobId = (String) clazz.getMethod("getJobId").invoke(taskAssignmentObj);
            String taskId = (String) clazz.getMethod("getTaskId").invoke(taskAssignmentObj);
            String range = (String) clazz.getMethod("getAssignedFrameRange").invoke(taskAssignmentObj);
            Object taskData = clazz.getMethod("getTaskData").invoke(taskAssignmentObj);

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
                java.io.File cacheDir = new java.io.File("./cache/" + jobId);
                if (!cacheDir.exists()) cacheDir.mkdirs();
                java.io.File cachedFile = new java.io.File(cacheDir, "scene.blend");
                java.nio.file.Files.write(cachedFile.toPath(), bytes);
                blendPath = cachedFile.getAbsolutePath();
                System.out.printf("[TASK] Unpacked binary blend file (%d bytes) to: %s\n", bytes.length, blendPath);
            }

            String renderEngine = "CYCLES";
            try {
                java.lang.reflect.Method getEngineMethod = clazz.getMethod("getRenderEngine");
                String engine = (String) getEngineMethod.invoke(taskAssignmentObj);
                if (engine != null && !engine.trim().isEmpty()) {
                    renderEngine = engine.trim();
                }
            } catch (NoSuchMethodException e) {
                // Fallback for older master-nodes sending payloads without getRenderEngine()
            }

            System.out.printf("[TASK] Received Task [%s] for Job [%s] (Frames: %d-%d, Blend: %s, Engine: %s)\n",
                taskId, jobId, start, end, blendPath, renderEngine);

            com.campusgrid.agent.blender.BlenderRenderTask renderTask = new com.campusgrid.agent.blender.BlenderRenderTask(
                jobId, blendPath, start, end, "./output/" + jobId, renderEngine
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
            java.util.List<String> renderedFiles = new java.util.ArrayList<>();
            
            com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = true;
            try {
                renderedFiles = com.campusgrid.agent.blender.BlenderJobExecutor.executeJob(
                    task.getJobId(),
                    task.getBlendFilePath(),
                    task.getFrameStart(),
                    task.getFrameEnd(),
                    task.getOutputDir(),
                    task.getRenderEngine(),
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
            com.campusgrid.agent.blender.RenderResult result = new com.campusgrid.agent.blender.RenderResult(
                task.getJobId(),
                reporter.getWorkerId(),
                renderedFiles,
                duration,
                status
            );

            // Read frame bytes if available
            byte[] frameBytes = new byte[0];
            if (!renderedFiles.isEmpty()) {
                java.io.File firstFrame = new java.io.File(renderedFiles.get(0));
                if (firstFrame.exists() && firstFrame.length() > 0) {
                    try {
                        frameBytes = java.nio.file.Files.readAllBytes(firstFrame.toPath());
                    } catch (Exception ignored) {}
                }
            }

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
}
