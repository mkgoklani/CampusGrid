package com.campusgrid.core;

import java.io.Serializable;

/**
 * Encapsulates the specific rendering properties for a Blender job.
 */
public class BlenderJobMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String requiredBlenderVersion; // e.g., "4.4"
    private final String renderEngine;           // e.g., "CYCLES" or "BLENDER_EEVEE"
    private final String resolution;             // e.g., "1920x1080"
    private final String outputFormat;           // e.g., "PNG"

    public BlenderJobMetadata(String requiredBlenderVersion, String renderEngine, String resolution, String outputFormat) {
        this.requiredBlenderVersion = requiredBlenderVersion;
        this.renderEngine = renderEngine;
        this.resolution = resolution;
        this.outputFormat = outputFormat;
    }

    public String getRequiredBlenderVersion() { return requiredBlenderVersion; }
    public String getRenderEngine() { return renderEngine; }
    public String getResolution() { return resolution; }
    public String getOutputFormat() { return outputFormat; }
}