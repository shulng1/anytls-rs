package com.anytls.app;

public final class AnyTlsNative {
    public interface Callback {
        void onListening(int port);

        void onLog(int level, String message);

        void onTraffic(long tx, long rx);

        void onStopped(int code);
    }

    static {
        System.loadLibrary("anytls_jni");
    }

    private AnyTlsNative() {
    }

    public static native int run(String commandLine, Callback callback);

    public static native int stop();

    public static native String generateUrl(String commandLine);
}
