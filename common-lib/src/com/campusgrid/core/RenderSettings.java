package com.campusgrid.core;

import java.io.Serializable;

/**
 * Immutable rendering settings for Blender jobs.
 */
public class RenderSettings implements Serializable {
    private static final long serialVersionUID = 1L;

    private final RenderEngine renderEngine;
    private final int resolutionX;
    private final int resolutionY;
    private final String outputFormat;
    private final int samples;
    private final int frameStart;
    private final int frameEnd;

    public RenderSettings(RenderEngine renderEngine, int resolutionX, int resolutionY, String outputFormat, int samples, int frameStart, int frameEnd) {
        this.renderEngine = renderEngine != null ? renderEngine : RenderEngine.CYCLES;
        this.resolutionX = resolutionX > 0 ? resolutionX : 1920;
        this.resolutionY = resolutionY > 0 ? resolutionY : 1080;
        this.outputFormat = outputFormat != null ? outputFormat : "PNG";
        this.samples = samples > 0 ? samples : 64;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
    }

    public RenderEngine getRenderEngine() {
        return renderEngine;
    }

    public int getResolutionX() {
        return resolutionX;
    }

    public int getResolutionY() {
        return resolutionY;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public int getSamples() {
        return samples;
    }

    public int getFrameStart() {
        return frameStart;
    }

    public int getFrameEnd() {
        return frameEnd;
    }

    @Override
    public String toString() {
        return String.format("RenderSettings[Engine=%s, Resolution=%dx%d, Format=%s, Samples=%d, Frames=%d-%d]",
            renderEngine, resolutionX, resolutionY, outputFormat, samples, frameStart, frameEnd);
    }
}
