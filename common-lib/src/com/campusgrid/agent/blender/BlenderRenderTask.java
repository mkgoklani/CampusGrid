package com.campusgrid.agent.blender;

import java.io.Serializable;

public class BlenderRenderTask implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String blendFilePath;
    private final int frameStart;
    private final int frameEnd;
    private final String outputDir;
    private final String renderEngine;
    private final int renderSamples;
    private final boolean useDenoising;
    private final int resolutionPercentage;

    public BlenderRenderTask(String jobId, String blendFilePath, int frameStart, int frameEnd, String outputDir, String renderEngine) {
        this(jobId, blendFilePath, frameStart, frameEnd, outputDir, renderEngine, 64, true, 100);
    }

    public BlenderRenderTask(String jobId, String blendFilePath, int frameStart, int frameEnd, String outputDir, String renderEngine,
                             int renderSamples, boolean useDenoising, int resolutionPercentage) {
        this.jobId = jobId;
        this.blendFilePath = blendFilePath;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
        this.outputDir = outputDir;
        this.renderEngine = (renderEngine != null && !renderEngine.trim().isEmpty()) ? renderEngine.trim() : "CYCLES";
        this.renderSamples = renderSamples > 0 ? renderSamples : 64;
        this.useDenoising = useDenoising;
        this.resolutionPercentage = resolutionPercentage > 0 ? resolutionPercentage : 100;
    }

    public String getJobId() { return jobId; }
    public String getBlendFilePath() { return blendFilePath; }
    public int getFrameStart() { return frameStart; }
    public int getFrameEnd() { return frameEnd; }
    public String getOutputDir() { return outputDir; }
    public String getRenderEngine() { return renderEngine; }
    public int getRenderSamples() { return renderSamples; }
    public boolean isUseDenoising() { return useDenoising; }
    public int getResolutionPercentage() { return resolutionPercentage; }

    @Override
    public String toString() {
        return String.format("BlenderRenderTask[jobId=%s, blendFilePath=%s, frames=%d-%d, engine=%s, samples=%d, denoise=%b, res=%d%%]",
            jobId, blendFilePath, frameStart, frameEnd, renderEngine, renderSamples, useDenoising, resolutionPercentage);
    }
}
