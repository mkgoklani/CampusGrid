package com.campusgrid.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Distributed Mandelbrot set computation task using an escape-time algorithm.
 * <p>
 * This task is designed for a Master-Worker model where the full image can be
 * split into horizontal strips, executed independently, and merged into a final
 * raw iteration matrix.
 */
public final class MandelbrotTask implements GridTask<int[][]> {

    private static final long serialVersionUID = 1L;

    private final double xMin;
    private final double xMax;
    private final int width;
    private final int height;
    private final int maxIterations;
    private final int globalRowOffset;
    private final double globalYMin;
    private final double globalYScale;

    public MandelbrotTask(
            double xMin,
            double xMax,
            double yMin,
            double yMax,
            int width,
            int height,
            int maxIterations
    ) {
        this(
                xMin,
                xMax,
                yMin,
                yMax,
                width,
                height,
                maxIterations,
                0,
                yMin,
                (yMax - yMin) / height
        );
    }

    private MandelbrotTask(
            double xMin,
            double xMax,
            double yMin,
            double yMax,
            int width,
            int height,
            int maxIterations,
            int globalRowOffset,
            double globalYMin,
            double globalYScale
    ) {
        if (xMax <= xMin) {
            throw new IllegalArgumentException("xMax must be greater than xMin.");
        }
        if (yMax <= yMin) {
            throw new IllegalArgumentException("yMax must be greater than yMin.");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("width must be greater than 0.");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be greater than 0.");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be greater than 0.");
        }
        if (globalRowOffset < 0) {
            throw new IllegalArgumentException("globalRowOffset must be non-negative.");
        }
        if (globalYScale <= 0.0d) {
            throw new IllegalArgumentException("globalYScale must be greater than 0.");
        }

        this.xMin = xMin;
        this.xMax = xMax;
        this.width = width;
        this.height = height;
        this.maxIterations = maxIterations;
        this.globalRowOffset = globalRowOffset;
        this.globalYMin = globalYMin;
        this.globalYScale = globalYScale;
    }

    @Override
    public List<GridTask<int[][]>> split(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be greater than 0.");
        }
        if (n > height) {
            throw new IllegalArgumentException("n must be less than or equal to height (" + height + ").");
        }

        List<GridTask<int[][]>> strips = new ArrayList<>(n);
        int rowsPerStrip = height / n;
        int remainder = height % n;
        int startRow = 0;
        for (int i = 0; i < n; i++) {
            int stripHeight = rowsPerStrip + (i == n - 1 ? remainder : 0);
            int endRow = startRow + stripHeight;
            int stripRowOffset = globalRowOffset + startRow;

            double stripYMin = globalYMin + (stripRowOffset * globalYScale);
            double stripYMax = globalYMin + ((globalRowOffset + endRow) * globalYScale);

            strips.add(new MandelbrotTask(
                    xMin,
                    xMax,
                    stripYMin,
                    stripYMax,
                    width,
                    stripHeight,
                    maxIterations,
                    stripRowOffset,
                    globalYMin,
                    globalYScale
            ));

            startRow = endRow;
        }

        return strips;
    }

    @Override
    public int[][] execute() {
        int[][] iterations = new int[width][height];
        double xScale = (xMax - xMin) / width;
        int rowOffset = globalRowOffset;
        double yBase = globalYMin;
        double yScale = globalYScale;

        for (int px = 0; px < width; px++) {
            double cReal = xMin + (px * xScale);
            for (int py = 0; py < height; py++) {
                double cImag = yBase + ((rowOffset + py) * yScale);

                double zReal = 0.0d;
                double zImag = 0.0d;
                int iteration = 0;

                while (iteration < maxIterations) {
                    double zRealSq = zReal * zReal;
                    double zImagSq = zImag * zImag;
                    if (zRealSq + zImagSq > 4.0d) {
                        break;
                    }

                    double newReal = zRealSq - zImagSq + cReal;
                    double newImag = (2.0d * zReal * zImag) + cImag;
                    zReal = newReal;
                    zImag = newImag;
                    iteration++;
                }

                iterations[px][py] = iteration;
            }
        }

        return iterations;
    }

    @Override
    public int[][] merge(List<int[][]> results) {
        if (results == null) {
            throw new IllegalArgumentException("results must not be null.");
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty.");
        }
        if (results.get(0) == null) {
            throw new IllegalArgumentException("results.get(0) must not be null.");
        }

        int[][] merged = new int[width][height];
        int destinationY = 0;

        for (int[][] strip : results) {
            if (strip == null) {
                throw new IllegalArgumentException("results must not contain null strips.");
            }
            if (strip.length != width) {
                throw new IllegalArgumentException("Each strip must have width " + width + ".");
            }

            if (strip[0] == null) {
                throw new IllegalArgumentException("Strip first column must not be null.");
            }
            int stripHeight = strip[0].length;
            if (destinationY + stripHeight > height) {
                throw new IllegalArgumentException("Combined strip height exceeds target height.");
            }

            for (int x = 0; x < width; x++) {
                if (strip[x] == null || strip[x].length != stripHeight) {
                    throw new IllegalArgumentException("All strip columns must be non-null and same height.");
                }
                System.arraycopy(strip[x], 0, merged[x], destinationY, stripHeight);
            }

            destinationY += stripHeight;
        }

        if (destinationY != height) {
            throw new IllegalArgumentException(
                    "Combined strip height (" + destinationY + ") does not match target height (" + height + ")."
            );
        }

        return merged;
    }
}
