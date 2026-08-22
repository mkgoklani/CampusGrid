package com.campusgrid.core;

/**
 * Minimal ANSI console helper for headless terminal rendering.
 */
public final class AnsiConsole {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";

    public static final String FG_GREEN = "\u001B[32m";
    public static final String FG_CYAN = "\u001B[36m";
    public static final String FG_YELLOW = "\u001B[33m";
    public static final String FG_RED = "\u001B[31m";
    public static final String FG_BRIGHT_BLACK = "\u001B[90m";

    private AnsiConsole() {
    }

    public static String cursorHome() {
        return "\u001B[H";
    }

    public static String moveTo(int row, int col) {
        int safeRow = row < 1 ? 1 : row;
        int safeCol = col < 1 ? 1 : col;
        return "\u001B[" + safeRow + ";" + safeCol + "H";
    }

    public static String clearScreen() {
        return "\u001B[2J";
    }

    public static String clearFromCursor() {
        return "\u001B[J";
    }

    public static String hideCursor() {
        return "\u001B[?25l";
    }

    public static String showCursor() {
        return "\u001B[?25h";
    }
}
