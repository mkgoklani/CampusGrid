package com.campusgrid.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 workload implementation for distributed Blender rendering.
 */
public class BlenderRenderTask implements GridTask<ResultPayload> {
    private static final long serialVersionUID = 1L;

    private final byte[] blendFileData;
    private final String blendFileName;
    private final BlenderJobMetadata metadata;
    
    private final int startFrame;
    private final int endFrame;

    public BlenderRenderTask(byte[] blendFileData, String blendFileName, BlenderJobMetadata metadata, int startFrame, int endFrame) {
        this.blendFileData = blendFileData;
        this.blendFileName = blendFileName;
        this.metadata = metadata;
        this.startFrame = startFrame;
        this.endFrame = endFrame;
    }

    @Override
    public List<GridTask<ResultPayload>> split(int partitions) {
        List<GridTask<ResultPayload>> subTasks = new ArrayList<>();
        int totalFrames = endFrame - startFrame + 1;
        int framesPerPartition = Math.max(1, totalFrames / partitions);

        int currentStart = startFrame;
        for (int i = 0; i < partitions; i++) {
            int currentEnd = (i == partitions - 1) ? endFrame : currentStart + framesPerPartition - 1;
            
            // Replicate the project data, but assign a specific frame slice
            subTasks.add(new BlenderRenderTask(
                blendFileData, blendFileName, metadata, currentStart, currentEnd
            ));
            
            currentStart = currentEnd + 1;
            if (currentStart > endFrame) break;
        }
        return subTasks;
    }

    @Override
    public ResultPayload execute() {
        // Fallback if executed without context (should not happen in Phase 2)
        throw new UnsupportedOperationException("Blender tasks require TaskContext to execute.");
    }

    @Override
    public ResultPayload execute(TaskContext context, ProgressReporter reporter) {
        if (reporter != null) {
            reporter.reportProgress(0.0, "Initializing Blender Render: Frames " + startFrame + "-" + endFrame);
        }

        // TODO: Agent OS Execution Logic Goes Here
        // 1. Write blendFileData to context.getWorkingDirectory()
        // 2. Build headless command using metadata
        // 3. Launch Process and monitor output for reporter.reportProgress()
        // 4. Zip the output PNGs into a byte[]
        
        byte[] dummyZip = new byte[0]; // Placeholder until we write the OS logic
        
        if (reporter != null) {
            reporter.reportProgress(100.0, "Render complete.");
        }
        
        return new ResultPayload(dummyZip, "Execution completed successfully.", true);
    }

    @Override
    public ResultPayload merge(List<ResultPayload> results) {
        // TODO: Merge logic to combine multiple zip files into one master archive
        return new ResultPayload(new byte[0], "Merged " + results.size() + " payloads.", true);
    }
}