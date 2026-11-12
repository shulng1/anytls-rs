package com.anytls.app;

import java.util.ArrayList;

public final class AppState {
    private static final int MAX_LOGS = 300;
    private static final ArrayList<String> LOGS = new ArrayList<>();
    private static volatile int logVersion;
    private static volatile boolean running;
    private static volatile boolean connecting;
    private static volatile String status = "未连接";
    private static volatile long tx;
    private static volatile long rx;

    private AppState() {
    }

    public static synchronized void reset() {
        LOGS.clear();
        logVersion++;
        running = false;
        connecting = true;
        status = "正在连接…";
        tx = 0;
        rx = 0;
    }

    public static synchronized void addLog(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        LOGS.add(line);
        while (LOGS.size() > MAX_LOGS) {
            LOGS.remove(0);
        }
        logVersion++;
    }

    public static synchronized String getLogText() {
        return String.join("\n", LOGS);
    }

    public static int getLogVersion() {
        return logVersion;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setRunning(boolean value) {
        running = value;
        if (value) {
            connecting = false;
        }
    }

    public static boolean isConnecting() {
        return connecting;
    }

    public static void setConnecting(boolean value) {
        connecting = value;
    }

    public static String getStatus() {
        return status;
    }

    public static void setStatus(String value) {
        status = value;
    }

    public static long getTx() {
        return tx;
    }

    public static long getRx() {
        return rx;
    }

    public static void setTraffic(long txValue, long rxValue) {
        tx = txValue;
        rx = rxValue;
    }
}
