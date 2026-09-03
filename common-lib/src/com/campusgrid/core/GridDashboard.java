package com.campusgrid.core;

import java.util.Map;
import java.util.TreeMap;

/**
 * ANSI telemetry dashboard renderer for CampusGrid.
 * <p>
 * Rendering is synchronized to prevent frame overlap under concurrent updates.
 */
public final class GridDashboard {

    private static final char[] BLOCKS = {' ', '\u2591', '\u2592', '\u2593', '\u2588'};

    private final int previewColumns;
    private final int previewRows;
    private final int barWidth;

    public GridDashboard() {
        this(72, 20, 42);
    }

    public GridDashboard(int previewColumns, int previewRows, int barWidth) {
        if (previewColumns <= 0 || previewRows <= 0 || barWidth <= 0) {
            throw new IllegalArgumentException("Dashboard dimensions must be > 0.");
        }
        this.previewColumns = previewColumns;
        this.previewRows = previewRows;
        this.barWidth = barWidth;
    }

    public synchronized void render(int totalProgress, Map<String, String> nodes, int[][] previewData) {
        int progress = clamp(totalProgress, 0, 100);
        Map<String, String> nodeSnapshot = nodes == null ? new TreeMap<String, String>() : new TreeMap<String, String>(nodes);

        StringBuilder frame = new StringBuilder(20_000);
        frame.append(AnsiConsole.cursorHome());

        appendHeader(frame);
        appendProgress(frame, progress);
        appendNodeTable(frame, nodeSnapshot);
        appendPreview(frame, previewData);

        frame.append(AnsiConsole.clearFromCursor());
        System.out.print(frame.toString());
        System.out.flush();
    }

    private void appendHeader(StringBuilder frame) {
        frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_CYAN)
                .append("=== CAMPUSGRID :: ANSI TELEMETRY DASHBOARD ===")
                .append(AnsiConsole.RESET).append('\n');
        frame.append(AnsiConsole.DIM).append(AnsiConsole.FG_BRIGHT_BLACK)
                .append("Distributed Mandelbrot Monitor / Matrix Mode")
                .append(AnsiConsole.RESET).append('\n').append('\n');
    }

    private void appendProgress(StringBuilder frame, int progress) {
        int filled = (progress * barWidth) / 100;

        frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_CYAN).append("[PROGRESS]").append(AnsiConsole.RESET).append('\n');
        frame.append('[');
        for (int i = 0; i < barWidth; i++) {
            if (i < filled) {
                frame.append(AnsiConsole.FG_GREEN).append('\u2588').append(AnsiConsole.RESET);
            } else {
                frame.append(AnsiConsole.FG_BRIGHT_BLACK).append('\u00B7').append(AnsiConsole.RESET);
            }
        }
        frame.append("] ")
                .append(AnsiConsole.BOLD).append(AnsiConsole.FG_CYAN)
                .append(String.format("%3d%%", progress))
                .append(AnsiConsole.RESET)
                .append('\n').append('\n');
    }

    private void appendNodeTable(StringBuilder frame, Map<String, String> nodes) {
        frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_CYAN).append("[NODES]").append(AnsiConsole.RESET).append('\n');
        frame.append("+-----------------+----------+----------+").append('\n');
        frame.append("| Worker IP       | State    | CPU Temp |").append('\n');
        frame.append("+-----------------+----------+----------+").append('\n');

        if (nodes.isEmpty()) {
            frame.append("| (none)          | ")
                    .append(AnsiConsole.FG_YELLOW).append("UNKNOWN ")
                    .append(AnsiConsole.RESET).append(" | ")
                    .append(AnsiConsole.FG_YELLOW).append("--      ").append(AnsiConsole.RESET)
                    .append(" |").append('\n');
        } else {
            for (Map.Entry<String, String> entry : nodes.entrySet()) {
                String ip = fit(entry.getKey(), 15);
                NodeTelemetry telemetry = parseTelemetry(entry.getValue());
                frame.append("| ").append(ip).append(" | ");
                appendState(frame, telemetry.state);
                frame.append(" | ");
                appendTemperature(frame, telemetry.cpuTempCelsius);
                frame.append(" |").append('\n');
            }
        }
        frame.append("+-----------------+----------+----------+").append('\n').append('\n');
    }

    private void appendState(StringBuilder frame, String state) {
        String display = fit(state, 8);
        if ("BUSY".equals(state) || "IDLE".equals(state)) {
            frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_GREEN).append(display).append(AnsiConsole.RESET);
        } else if ("EVICTED".equals(state)) {
            frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_RED).append(display).append(AnsiConsole.RESET);
        } else if ("OFFLINE".equals(state)) {
            frame.append(AnsiConsole.DIM).append(AnsiConsole.FG_RED).append(display).append(AnsiConsole.RESET);
        } else {
            frame.append(AnsiConsole.FG_YELLOW).append(display).append(AnsiConsole.RESET);
        }
    }

    private void appendTemperature(StringBuilder frame, int cpuTempCelsius) {
        if (cpuTempCelsius < 0) {
            frame.append(AnsiConsole.FG_YELLOW).append(fit("--", 8)).append(AnsiConsole.RESET);
            return;
        }

        String display = fit(cpuTempCelsius + "\u00B0C", 8);
        if (cpuTempCelsius >= 80) {
            frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_RED).append(display).append(AnsiConsole.RESET);
        } else if (cpuTempCelsius >= 65) {
            frame.append(AnsiConsole.FG_YELLOW).append(display).append(AnsiConsole.RESET);
        } else {
            frame.append(AnsiConsole.FG_GREEN).append(display).append(AnsiConsole.RESET);
        }
    }

    private void appendPreview(StringBuilder frame, int[][] previewData) {
        frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_CYAN).append("[FRACTAL PREVIEW]").append(AnsiConsole.RESET).append('\n');
        if (previewData == null || previewData.length == 0 || previewData[0] == null || previewData[0].length == 0) {
            frame.append(AnsiConsole.FG_YELLOW).append("(preview unavailable)").append(AnsiConsole.RESET).append('\n');
            return;
        }

        int sourceWidth = previewData.length;
        int sourceHeight = previewData[0].length;
        int maxIteration = findMax(previewData);
        if (maxIteration <= 0) {
            maxIteration = 1;
        }

        for (int y = 0; y < previewRows; y++) {
            int sourceY = mapIndex(y, previewRows, sourceHeight);
            for (int x = 0; x < previewColumns; x++) {
                int sourceX = mapIndex(x, previewColumns, sourceWidth);
                int value = safeValue(previewData, sourceX, sourceY);
                appendPreviewCell(frame, value, maxIteration);
            }
            frame.append(AnsiConsole.RESET).append('\n');
        }
    }

    private void appendPreviewCell(StringBuilder frame, int value, int maxIteration) {
        double ratio = value / (double) maxIteration;
        int bucket = (int) Math.floor(ratio * (BLOCKS.length - 1));
        if (bucket < 0) {
            bucket = 0;
        } else if (bucket >= BLOCKS.length) {
            bucket = BLOCKS.length - 1;
        }
        char block = BLOCKS[bucket];

        if (bucket <= 1) {
            frame.append(AnsiConsole.DIM).append(AnsiConsole.FG_BRIGHT_BLACK).append(block);
        } else if (bucket == 2) {
            frame.append(AnsiConsole.FG_CYAN).append(block);
        } else if (bucket == 3) {
            frame.append(AnsiConsole.FG_GREEN).append(block);
        } else {
            frame.append(AnsiConsole.BOLD).append(AnsiConsole.FG_GREEN).append(block);
        }
    }

    private int findMax(int[][] data) {
        int max = 0;
        for (int x = 0; x < data.length; x++) {
            int[] column = data[x];
            if (column == null) {
                continue;
            }
            for (int y = 0; y < column.length; y++) {
                if (column[y] > max) {
                    max = column[y];
                }
            }
        }
        return max;
    }

    private int safeValue(int[][] data, int x, int y) {
        if (x < 0 || x >= data.length || data[x] == null) {
            return 0;
        }
        if (y < 0 || y >= data[x].length) {
            return 0;
        }
        int value = data[x][y];
        return value < 0 ? 0 : value;
    }

    private int mapIndex(int outIndex, int outSize, int inSize) {
        if (inSize <= 1 || outSize <= 1) {
            return 0;
        }
        return (outIndex * (inSize - 1)) / (outSize - 1);
    }

    private String normalizeState(String raw) {
        if (raw == null) {
            return "UNKNOWN";
        }
        String normalized = raw.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return "UNKNOWN";
        }
        return normalized;
    }

    private NodeTelemetry parseTelemetry(String rawValue) {
        if (rawValue == null) {
            return new NodeTelemetry("UNKNOWN", -1);
        }

        String raw = rawValue.trim();
        if (raw.isEmpty()) {
            return new NodeTelemetry("UNKNOWN", -1);
        }

        String statePart = raw;
        String tempPart = null;

        int pipeIndex = raw.indexOf('|');
        if (pipeIndex >= 0) {
            statePart = raw.substring(0, pipeIndex);
            tempPart = raw.substring(pipeIndex + 1);
        } else {
            int commaIndex = raw.indexOf(',');
            if (commaIndex >= 0) {
                statePart = raw.substring(0, commaIndex);
                tempPart = raw.substring(commaIndex + 1);
            }
        }

        String state = normalizeState(statePart);
        int temp = parseCpuTemp(tempPart);
        return new NodeTelemetry(state, temp);
    }

    private int parseCpuTemp(String rawTemp) {
        if (rawTemp == null) {
            return -1;
        }

        StringBuilder digits = new StringBuilder(4);
        String trimmed = rawTemp.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }

        if (digits.length() == 0) {
            return -1;
        }

        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String fit(String value, int width) {
        if (value == null) {
            value = "";
        }
        if (value.length() > width) {
            return value.substring(0, width);
        }
        StringBuilder out = new StringBuilder(width);
        out.append(value);
        while (out.length() < width) {
            out.append(' ');
        }
        return out.toString();
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static final class NodeTelemetry {
        private final String state;
        private final int cpuTempCelsius;

        private NodeTelemetry(String state, int cpuTempCelsius) {
            this.state = state;
            this.cpuTempCelsius = cpuTempCelsius;
        }
    }
}
