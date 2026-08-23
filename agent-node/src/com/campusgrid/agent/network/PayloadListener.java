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
                    System.out.println("[TASK] Executing...");

                    // Report BUSY status during GridTask calculation
                    try {
                        com.campusgrid.agent.blender.ProgressReporter busyReporter = 
                            new com.campusgrid.agent.blender.ProgressReporter(connection);
                        String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
                        busyReporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "BUSY", blenderVer, true);
                    } catch (Exception e) {
                        // ignore
                    }

                    com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = true;
                    Object result = task.execute();
                    com.campusgrid.agent.os.LinuxTelemetry.isExecutingTask = false;

                    connection.sendObject(result);
                    System.out.println("[TASK] Result sent");

                    // Restore READY status after execution
                    try {
                        com.campusgrid.agent.blender.ProgressReporter readyReporter = 
                            new com.campusgrid.agent.blender.ProgressReporter(connection);
                        String blenderVer = com.campusgrid.agent.blender.BlenderInstaller.getInstallationStatus().getVersion();
                        readyReporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "READY", blenderVer, true);
                    } catch (Exception e) {
                        // ignore
                    }
                } else if (obj instanceof com.campusgrid.agent.blender.BlenderRenderTask) {
                    com.campusgrid.agent.blender.BlenderRenderTask task = (com.campusgrid.agent.blender.BlenderRenderTask) obj;
                    System.out.println("[TASK] Blender render task received: " + task.getJobId());
                    
                    // Route render job to executor in a separate thread
                    handleAsyncRender(task);
                } else if (obj instanceof com.campusgrid.agent.blender.BlenderJob) {
                    // Fallback support for generic BlenderJob
                    com.campusgrid.agent.blender.BlenderJob job = (com.campusgrid.agent.blender.BlenderJob) obj;
                    System.out.println("[TASK] Legacy Blender job received: " + job.getJobId());
                    
                    // Wrap into a BlenderRenderTask and run
                    com.campusgrid.agent.blender.BlenderRenderTask wrapped = new com.campusgrid.agent.blender.BlenderRenderTask(
                        job.getJobId(), job.getBlendFilePath(), job.getFrameStart(), job.getFrameEnd(), job.getOutputDir(), job.getRenderEngine()
                    );
                    handleAsyncRender(wrapped);
                } else if (isKillCommand(obj)) {
                    String targetJobId = extractJobIdFromPacket(obj);
                    System.out.println("[TASK] Kill/Cancel command received for jobId: " + targetJobId);
                    
                    synchronized (this) {
                        if (targetJobId == null || targetJobId.equals(currentJobId)) {
                            if (currentJobId != null) {
                                com.campusgrid.agent.blender.BlenderJobExecutor.cancelJob(currentJobId);
                            }
                            if (currentRenderThread != null && currentRenderThread.isAlive()) {
                                currentRenderThread.interrupt();
                            }
                        }
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("[TASK] Connection lost.");
                stop();
                break;
            }
        }
    }

    /**
     * Executes the Blender render task in a separate worker thread.
     * Prevents blocking of the main thread and heartbeat service.
     *
     * @param task the BlenderRenderTask details.
     */
    private synchronized void handleAsyncRender(com.campusgrid.agent.blender.BlenderRenderTask task) {
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
                
                // Clear tracking if this is still the active thread
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

            try {
                connection.sendObject(result);
                System.out.println("[TASK] Render result sent to Master: " + result);
            } catch (IOException e) {
                System.err.println("[TASK] Failed to send render result: " + e.getMessage());
            }

            // Restore READY status after execution
            try {
                reporter.reportStatus("N/A", 0, 0, 0.0, -1.0, "READY", blenderVer, true);
            } catch (Exception e) {
                // ignore
            }
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
