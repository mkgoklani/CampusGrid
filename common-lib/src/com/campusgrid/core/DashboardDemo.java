package com.campusgrid.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo runner that simulates a 10-node cluster with live telemetry for 10 seconds.
 */
public final class DashboardDemo {

    private DashboardDemo() {
    }

    public static void main(String[] args) {
        GridDashboard dashboard = new GridDashboard(72, 20, 42);
        Map<String, String> nodes = createNodeMap();
        int[][] preview = new int[192][108];

        long durationMs = 10_000L;
        long frameDelayMs = 200L;
        long start = System.currentTimeMillis();
        int tick = 0;

        System.out.print(AnsiConsole.clearScreen());
        System.out.print(AnsiConsole.cursorHome());
        System.out.print(AnsiConsole.hideCursor());
        System.out.flush();

        try {
            while (true) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > durationMs) {
                    elapsed = durationMs;
                }

                int progress = (int) ((elapsed * 100L) / durationMs);
                updateNodeTelemetry(nodes, tick, progress);
                updatePreview(preview, tick, progress);
                dashboard.render(progress, nodes, preview);

                if (elapsed >= durationMs) {
                    break;
                }

                Thread.sleep(frameDelayMs);
                tick++;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Dashboard demo interrupted.");
        } finally {
            System.out.print(AnsiConsole.showCursor());
            System.out.print(AnsiConsole.moveTo(30, 1));
            System.out.flush();
        }
    }

    private static Map<String, String> createNodeMap() {
        Map<String, String> nodes = new LinkedHashMap<String, String>();
        nodes.put("10.0.0.11", "IDLE|45°C");
        nodes.put("10.0.0.12", "IDLE|46°C");
        nodes.put("10.0.0.13", "IDLE|44°C");
        nodes.put("10.0.0.14", "IDLE|43°C");
        nodes.put("10.0.0.15", "IDLE|47°C");
        nodes.put("10.0.0.16", "IDLE|48°C");
        nodes.put("10.0.0.17", "IDLE|44°C");
        nodes.put("10.0.0.18", "IDLE|45°C");
        nodes.put("10.0.0.19", "IDLE|46°C");
        nodes.put("10.0.0.20", "IDLE|47°C");
        return nodes;
    }

    private static void updateNodeTelemetry(Map<String, String> nodes, int tick, int progress) {
        int index = 0;
        for (String ip : nodes.keySet()) {
            int temp = 42 + ((index * 3 + tick * 2 + progress / 6) % 34);
            String state;

            if ((tick + index) % 17 == 0 && progress < 96) {
                state = "EVICTED";
                temp = 82 + ((tick + index) % 7);
            } else if ((tick + index) % 3 == 0) {
                state = "BUSY";
                temp += 8;
            } else {
                state = "IDLE";
            }

            if (temp > 95) {
                temp = 95;
            }

            nodes.put(ip, state + "|" + temp + "°C");
            index++;
        }
    }

    private static void updatePreview(int[][] data, int tick, int progress) {
        int width = data.length;
        int height = data[0].length;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                double wave = Math.sin((x + (tick * 3)) * 0.08d) + Math.cos((y - (tick * 2)) * 0.11d);
                double radial = Math.sin(Math.sqrt((x - width / 2.0d) * (x - width / 2.0d)
                        + (y - height / 2.0d) * (y - height / 2.0d)) * 0.06d - (tick * 0.2d));
                int value = (int) ((wave + radial + 3.0d) * 42.0d);
                value += progress / 2;
                if (value < 0) {
                    value = 0;
                } else if (value > 255) {
                    value = 255;
                }
                data[x][y] = value;
            }
        }
    }
}
