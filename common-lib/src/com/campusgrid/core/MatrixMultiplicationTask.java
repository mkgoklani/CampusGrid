package com.campusgrid.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed Matrix Multiplication task representing foundational AI/Data Science arithmetic.
 * Represents the multiplication of two massive 4,000 x 4,000 matrices.
 * 
 * Performance Design:
 * Instead of passing massive matrices across the network (128 MB raw payload), this task
 * generates matrices A and B deterministically in memory using algebraic index generators.
 * Only the computed row slice (e.g. 800 x 4,000 doubles) is sent back to the Master, 
 * optimizing throughput and preventing network bottlenecking.
 */
public final class MatrixMultiplicationTask implements GridTask<double[][]> {

    private static final long serialVersionUID = 1L;

    private final int size;
    private final int rowStart;
    private final int rowEnd;

    /**
     * Constructs a MatrixMultiplicationTask for the entire matrix of dimension size x size.
     *
     * @param size dimension of the square matrices.
     */
    public MatrixMultiplicationTask(int size) {
        this(size, 0, size);
    }

    /**
     * Private constructor used to define horizontal row slices.
     */
    private MatrixMultiplicationTask(int size, int rowStart, int rowEnd) {
        if (size <= 0) {
            throw new IllegalArgumentException("Matrix size must be greater than 0");
        }
        if (rowStart < 0 || rowEnd > size || rowStart > rowEnd) {
            throw new IllegalArgumentException("Invalid row bounds: [" + rowStart + ", " + rowEnd + "]");
        }
        this.size = size;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
    }

    @Override
    public List<GridTask<double[][]>> split(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be greater than 0");
        }
        if (n > size) {
            throw new IllegalArgumentException("Cannot split into more slices than rows");
        }

        List<GridTask<double[][]>> subTasks = new ArrayList<>(n);
        int rowsPerSlice = size / n;
        int remainder = size % n;
        int start = 0;

        for (int i = 0; i < n; i++) {
            int end = start + rowsPerSlice + (i == n - 1 ? remainder : 0);
            subTasks.add(new MatrixMultiplicationTask(size, start, end));
            start = end;
        }
        return subTasks;
    }

    @Override
    public double[][] execute() {
        int subHeight = rowEnd - rowStart;
        double[][] result = new double[subHeight][size];

        // 1. Instantiates matrix B deterministically in memory (constant access cache)
        double[][] B = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                B[i][j] = (double) (i * 2 - j) / size;
            }
        }

        // 2. Perform Row-by-Column multiplication for assigned rows of A
        for (int i = 0; i < subHeight; i++) {
            int globalRow = rowStart + i;
            
            // Generate row of A deterministically
            double[] rowA = new double[size];
            for (int j = 0; j < size; j++) {
                rowA[j] = (double) (globalRow + j * 3) / size;
            }

            // Multiply rowA by B
            for (int col = 0; col < size; col++) {
                double accum = 0.0;
                for (int k = 0; k < size; k++) {
                    accum += rowA[k] * B[k][col];
                }
                result[i][col] = accum;
            }
        }
        return result;
    }

    @Override
    public double[][] merge(List<double[][]> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("Results list cannot be null or empty");
        }

        double[][] merged = new double[size][size];
        int currentDstRow = 0;

        for (double[][] slice : results) {
            if (slice == null) {
                throw new IllegalArgumentException("Encountered null result matrix slice");
            }
            int sliceHeight = slice.length;
            if (currentDstRow + sliceHeight > size) {
                throw new IllegalArgumentException("Aggregated slice heights exceed global matrix size");
            }

            for (int i = 0; i < sliceHeight; i++) {
                System.arraycopy(slice[i], 0, merged[currentDstRow + i], 0, size);
            }
            currentDstRow += sliceHeight;
        }

        if (currentDstRow != size) {
            throw new IllegalArgumentException("Merged row count (" + currentDstRow + ") does not match global size (" + size + ")");
        }
        return merged;
    }
}
