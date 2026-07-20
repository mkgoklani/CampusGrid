package com.campusgrid.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MathAudit {
    public static void main(String[] args) {
        System.out.println("\u001B[34m[AUDIT] Starting Mathematical Integrity Check...\u001B[0m");

        // Test Parameters
        int width = 4000;
        int height = 4000;
        int maxIter = 255;
        int numSplits = 7; // Use a prime number to stress-test the remainder logic

        // 1. Generate Baseline (Single Task)
        MandelbrotTask originalTask = new MandelbrotTask(-2.0, 1.0, -1.0, 1.0, width, height, maxIter);
        int[][] baseline = originalTask.execute();

        // 2. Generate Distributed Result (Split and Merge)
        List<GridTask<int[][]>> strips = originalTask.split(numSplits);
        List<int[][]> results = new ArrayList<>();
        
        for (GridTask<int[][]> strip : strips) {
            results.add(strip.execute());
        }
        
        int[][] merged = originalTask.merge(results);

        // 3. Verification
        boolean isPerfect = true;
        for (int i = 0; i < width; i++) {
            if (!Arrays.equals(baseline[i], merged[i])) {
                System.err.println("\u001B[31m[FAILURE] Mismatch found at column: " + i + "\u001B[0m");
                isPerfect = false;
                break;
            }
        }

        if (isPerfect) {
            System.out.println("\u001B[32m[SUCCESS] Mathematical Integrity Verified. No seams detected.\u001B[0m");
        } else {
            System.out.println("\u001B[31m[CRITICAL] Audit Failed. Check Slicer row calculations.\u001B[0m");
            System.exit(1);
        }
    }
}