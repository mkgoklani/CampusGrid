package com.campusgrid.agent.blender;

import java.io.Serializable;

/**
 * Represents a Blender rendering task payload received from the Master.
 */
public class BlenderRenderTask implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String blendFilePath;
    private final int frameStart;
    private final int frameEnd;
    private final String outputDir;
    private final String renderEngine;

    public BlenderRenderTask(String jobId, String blendFilePath, int frameStart, int frameEnd, String outputDir, String renderEngine) {
        this.jobId = jobId;
        this.blendFilePath = blendFilePath;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
        this.outputDir = outputDir;
        this.renderEngine = renderEngine;
    }

    public String getJobId() { return jobId; }
    public String getBlendFilePath() { return blendFilePath; }
    public int getFrameStart() { return frameStart; }
    public int getFrameEnd() { return frameEnd; }
    public String getOutputDir() { return outputDir; }
    public String getRenderEngine() { return renderEngine; }

    @Override
    public String toString() {
        return String.format("BlenderRenderTask[jobId=%s, blendFilePath=%s, frames=%d-%d, outputDir=%s, renderEngine=%s]",
            jobId, blendFilePath, frameStart, frameEnd, outputDir, renderEngine);
    }
}
