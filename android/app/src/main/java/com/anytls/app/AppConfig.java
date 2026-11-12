package com.anytls.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class AppConfig {
    private static final String PREFS = "anytls";
    private static final String KEY_SERVER = "server";
    private static final String KEY_PORT = "port";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SNI = "sni";
    private static final String KEY_INSECURE = "insecure";
    private static final String KEY_SOCKS_PORT = "socks_port";
    private static final String ROOT_CERT_FILE = "ca-bundle.pem";

    private final Context context;
    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getServer() {
        return prefs.getString(KEY_SERVER, "");
    }

    public int getPort() {
        return prefs.getInt(KEY_PORT, 443);
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public String getSni() {
        return prefs.getString(KEY_SNI, "");
    }

    public boolean isInsecure() {
        return prefs.getBoolean(KEY_INSECURE, false);
    }

    public int getSocksPort() {
        return prefs.getInt(KEY_SOCKS_PORT, 1080);
    }

    public void save(String server, int port, String password, String sni, boolean insecure, int socksPort) {
        prefs.edit()
                .putString(KEY_SERVER, server)
                .putInt(KEY_PORT, port)
                .putString(KEY_PASSWORD, password)
                .putString(KEY_SNI, sni)
                .putBoolean(KEY_INSECURE, insecure)
                .putInt(KEY_SOCKS_PORT, socksPort)
                .commit();
    }

    public String buildCommandLine() {
        String host = getServer().trim();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }

        StringBuilder command = new StringBuilder();
        command.append("anytls-client")
                .append(" --server ").append(host).append(':').append(getPort())
                .append(" --password ").append(shellQuote(getPassword()))
                .append(" --listen mixed://127.0.0.1:").append(getSocksPort())
                .append(" --log info");

        String sni = getSni().trim();
        if (!sni.isEmpty()) {
            command.append(" --sni ").append(shellQuote(sni));
        }

        // Android's system CA directories are not detected by the TLS stack,
        // so hand the bundled Mozilla CA bundle to the native client.
        // A root certificate forces secure TLS, so it is skipped in insecure mode.
        if (isInsecure()) {
            command.append(" --insecure");
        } else {
            File rootCert = ensureRootCert();
            if (rootCert != null) {
                command.append(" --root-cert ").append(shellQuote(rootCert.getAbsolutePath()));
            }
        }
        return command.toString();
    }

    private File ensureRootCert() {
        File out = new File(context.getFilesDir(), ROOT_CERT_FILE);
        if (out.isFile() && out.length() > 0) {
            return out;
        }
        try (InputStream in = context.getAssets().open(ROOT_CERT_FILE);
             FileOutputStream stream = new FileOutputStream(out, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                stream.write(buffer, 0, read);
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.2f %s", value, units[unit]);
    }
}
