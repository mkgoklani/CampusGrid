package com.campusgrid.agent.blender;

import java.io.Serializable;
import com.campusgrid.core.RenderSettings;

/**
 * Represents a Blender rendering task payload received from the Master.
 */
public class BlenderRenderTask implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String blendFilePath;
    private final String outputDir;
    private final RenderSettings settings;

    /**
     * Constructs a new BlenderRenderTask.
     *
     * @param jobId         the unique render job identifier.
     * @param blendFilePath the path to the target .blend file.
     * @param frameStart    the starting frame index.
     * @param frameEnd      the ending frame index.
     * @param outputDir     the directory to write rendered frames.
     * @param renderEngine  the render engine (e.g. CYCLES, BLENDER_EEVEE).
     */
    public BlenderRenderTask(String jobId, String blendFilePath, int frameStart, int frameEnd, String outputDir, String renderEngine) {
        this.jobId = jobId;
        this.blendFilePath = blendFilePath;
        this.outputDir = outputDir;
        com.campusgrid.core.RenderEngine engineEnum = com.campusgrid.core.RenderEngine.CYCLES;
        try {
            String norm = renderEngine.trim().toUpperCase();
            if (norm.contains("EEVEE")) {
                engineEnum = com.campusgrid.core.RenderEngine.EEVEE;
            } else if (norm.contains("WORKBENCH")) {
                engineEnum = com.campusgrid.core.RenderEngine.WORKBENCH;
            } else if (norm.contains("CYCLES")) {
                engineEnum = com.campusgrid.core.RenderEngine.CYCLES;
            } else {
                engineEnum = com.campusgrid.core.RenderEngine.valueOf(norm);
            }
        } catch (Exception ignored) {}
        this.settings = new RenderSettings(engineEnum, 1920, 1080, "PNG", 64, frameStart, frameEnd);
    }

    /**
     * Constructs a new BlenderRenderTask wrapping RenderSettings.
     *
     * @param jobId         the unique render job identifier.
     * @param blendFilePath the path to the target .blend file.
     * @param outputDir     the directory to write rendered frames.
     * @param settings      the render settings object.
     */
    public BlenderRenderTask(String jobId, String blendFilePath, String outputDir, RenderSettings settings) {
        this.jobId = jobId;
        this.blendFilePath = blendFilePath;
        this.outputDir = outputDir;
        this.settings = settings;
    }

    /**
     * Gets the job ID.
     *
     * @return the job ID.
     */
    public String getJobId() {
        return jobId;
    }

    /**
     * Gets the blend file path.
     *
     * @return the blend file path.
     */
    public String getBlendFilePath() {
        return blendFilePath;
    }

    /**
     * Gets the starting frame index.
     *
     * @return the start frame.
     */
    public int getFrameStart() {
        return settings != null ? settings.getFrameStart() : 1;
    }

    /**
     * Gets the ending frame index.
     *
     * @return the end frame.
     */
    public int getFrameEnd() {
        return settings != null ? settings.getFrameEnd() : 1;
    }

    /**
     * Gets the output directory path.
     *
     * @return the output directory.
     */
    public String getOutputDir() {
        return outputDir;
    }

    /**
     * Gets the render settings.
     *
     * @return the render settings object.
     */
    public RenderSettings getSettings() {
        return settings;
    }

    /**
     * Gets the render engine name.
     *
     * @return the render engine.
     */
    public String getRenderEngine() {
        return settings != null && settings.getRenderEngine() != null ? settings.getRenderEngine().name() : "CYCLES";
    }

    @Override
    public String toString() {
        return String.format("BlenderRenderTask[jobId=%s, blendFilePath=%s, outputDir=%s, settings=%s]",
            jobId, blendFilePath, outputDir, settings);
    }
}
