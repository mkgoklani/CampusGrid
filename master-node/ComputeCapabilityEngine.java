import java.util.*;
import com.campusgrid.core.WorkerStatus;

/**
 * CAMPUS GRID - COMPUTE CAPABILITY ENGINE
 * 
 * Evaluates worker node specifications (GPU model, compute backend, CPU cores)
 * to compute normalized performance weights and partition animation frames
 * proportionally across heterogeneous cluster nodes.
 */
public class ComputeCapabilityEngine {

    /**
     * Calculates the normalized Compute Capability Score for a worker node.
     * Higher score = faster rendering capacity = more frames allocated.
     *
     * @param worker The worker node to evaluate.
     * @return Multiplier score (e.g., 4.5 for RTX GPU, 1.0 for CPU).
     */
    public static double calculateScore(WorkerState worker) {
        if (worker == null || worker.getStatus() == WorkerStatus.OFFLINE) {
            return 1.0;
        }

        if (!worker.isUseGpu()) {
            return 1.0; // Worker has manually disabled GPU acceleration
        }

        String gpu = (worker.getGpuModel() != null) ? worker.getGpuModel().toUpperCase() : "CPU";
        String computeType = (worker.getGpuComputeType() != null) ? worker.getGpuComputeType().toUpperCase() : "NONE";

        // 1. High-End Discrete GPU (NVIDIA RTX / OptiX / CUDA)
        if (gpu.contains("RTX 40") || gpu.contains("RTX 30") || gpu.contains("RTX 20") || 
            gpu.contains("TITAN") || gpu.contains("A100") || gpu.contains("A4000") || 
            gpu.contains("QUADRO") || computeType.equals("OPTIX")) {
            return 4.5;
        } else if (gpu.contains("GTX") || computeType.equals("CUDA") || gpu.contains("NVIDIA")) {
            return 3.5;
        } 
        // 2. High-Performance Unified / AMD Radeon / Apple Silicon / Intel Arc
        else if (computeType.equals("METAL") || gpu.contains("APPLE") || gpu.contains("M1") || gpu.contains("M2") || gpu.contains("M3") || gpu.contains("M4") ||
                 gpu.contains("RADEON RX") || gpu.contains("ARC") || computeType.equals("HIP")) {
            return 3.0;
        }
        // 3. Integrated GPU (Intel Iris Xe, UHD, AMD Radeon 740M/760M/780M, etc.)
        else if (gpu.contains("RADEON") || gpu.contains("IRIS") || gpu.contains("UHD") || computeType.equals("ONEAPI") || gpu.contains("VEGA")) {
            return 1.8;
        }
        // 4. Pure CPU fallback
        else {
            return 1.0;
        }
    }

    /**
     * Computes spec-weighted frame slices across active workers for a given total frame count.
     *
     * @param totalFrames Total frames in the render job.
     * @param workers List of active worker nodes.
     * @return List of frame counts partitioned proportionally by hardware capability.
     */
    public static List<Integer> partitionFrames(int totalFrames, List<WorkerState> workers) {
        List<Integer> slices = new ArrayList<>();
        if (workers == null || workers.isEmpty()) {
            slices.add(totalFrames);
            return slices;
        }

        if (workers.size() == 1 || totalFrames <= 1) {
            slices.add(totalFrames);
            return slices;
        }

        double totalScore = 0.0;
        double[] scores = new double[workers.size()];
        for (int i = 0; i < workers.size(); i++) {
            scores[i] = calculateScore(workers.get(i));
            totalScore += scores[i];
        }

        if (totalScore <= 0.0) {
            totalScore = workers.size();
            Arrays.fill(scores, 1.0);
        }

        int allocatedFrames = 0;
        for (int i = 0; i < workers.size(); i++) {
            // Proportional allocation: totalFrames * (score / totalScore)
            int count = (int) Math.round((double) totalFrames * (scores[i] / totalScore));
            count = Math.max(1, count); // Guarantee at least 1 frame per node
            slices.add(count);
            allocatedFrames += count;
        }

        // Adjust remainder so sum of slices matches totalFrames exactly
        int difference = totalFrames - allocatedFrames;
        if (difference != 0) {
            // Find highest scoring worker index to absorb the remainder
            int maxIdx = 0;
            for (int i = 1; i < scores.length; i++) {
                if (scores[i] > scores[maxIdx]) maxIdx = i;
            }
            int updated = slices.get(maxIdx) + difference;
            if (updated >= 1) {
                slices.set(maxIdx, updated);
            } else {
                slices.set(0, Math.max(1, slices.get(0) + difference));
            }
        }

        return slices;
    }
}
