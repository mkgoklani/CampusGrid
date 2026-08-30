package com.campusgrid.agent.blender;

/**
 * Callback interface to listen for local Blender rendering progress updates.
 */
public interface ProgressListener {
    void onProgress(int currentFrame, double percentage, double renderFps);
}
