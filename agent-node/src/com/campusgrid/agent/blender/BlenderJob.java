package com.campusgrid.agent.blender;

import java.io.Serializable;

/**
 * Represents a Blender rendering job received from the Master node.
 * This class is serializable so it can be transmitted over Object streams.
 */
public class BlenderJob implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String blendFilePath;
    private final int frameStart;
    private final int frameEnd;
    private final String outputDir;
    private final String renderEngine;

    /**
     * Constructs a new Blender render job.
     *
     * @param jobId         the unique identifier for the job.
     * @param blendFilePath the absolute path to the .blend file.
     * @param frameStart    the starting frame index.
     * @param frameEnd      the ending frame index.
     * @param outputDir     the output directory for the rendered frames.
     * @param renderEngine  the Blender render engine to use (e.g. CYCLES, BLENDER_EEVEE).
     */
    public BlenderJob(String jobId, String blendFilePath, int frameStart, int frameEnd, String outputDir, String renderEngine) {
        this.jobId = jobId;
        this.blendFilePath = blendFilePath;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
        this.outputDir = outputDir;
        this.renderEngine = renderEngine;
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
        return frameStart;
    }

    /**
     * Gets the ending frame index.
     *
     * @return the end frame.
     */
    public int getFrameEnd() {
        return frameEnd;
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
     * Gets the render engine name.
     *
     * @return the render engine.
     */
    public String getRenderEngine() {
        return renderEngine;
    }

    @Override
    public String toString() {
        return String.format("BlenderJob[ID=%s, Blend=%s, Frames=%d-%d, Out=%s, Engine=%s]",
            jobId, blendFilePath, frameStart, frameEnd, outputDir, renderEngine);
    }
}
