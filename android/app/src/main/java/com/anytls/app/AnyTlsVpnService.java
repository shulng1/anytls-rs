package com.anytls.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import hev.htproxy.TProxyService;

public class AnyTlsVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.anytls.app.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.anytls.app.action.DISCONNECT";

    private static final String CHANNEL_ID = "anytls_status";
    private static final int NOTIFICATION_ID = 1;

    private final Object cleanupLock = new Object();
    private ParcelFileDescriptor tunFd;
    private boolean cleaned;
    private volatile boolean connecting;
    private volatile boolean stopping;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_CONNECT;
        if (ACTION_DISCONNECT.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        createChannel();
        startForegroundCompat("正在启动代理…");

        if (connecting || AppState.isRunning()) {
            return START_STICKY;
        }

        connecting = true;
        stopping = false;
        AppState.reset();
        AppState.setStatus("正在启动本地 SOCKS5…");
        AppState.addLog("正在连接 " + new AppConfig(this).getServer() + " …");

        Thread worker = new Thread(this::runClient, "anytls-client");
        worker.start();
        return START_STICKY;
    }

    private void runClient() {
        AppConfig config = new AppConfig(this);
        String commandLine = config.buildCommandLine();

        AnyTlsNative.Callback callback = new AnyTlsNative.Callback() {
            @Override
            public void onListening(int port) {
                if (stopping) {
                    AnyTlsNative.stop();
                    return;
                }
                AppState.addLog("本地 SOCKS5 已监听 127.0.0.1:" + port);
                new Thread(() -> startTun(port), "anytls-tun").start();
            }

            @Override
            public void onLog(int level, String message) {
                AppState.addLog(message);
            }

            @Override
            public void onTraffic(long tx, long rx) {
                AppState.setTraffic(tx, rx);
            }

            @Override
            public void onStopped(int code) {
                AppState.addLog("AnyTLS 客户端已退出（code=" + code + "）");
            }
        };

        int result = AnyTlsNative.run(commandLine, callback);
        connecting = false;

        if (!stopping && !AppState.isRunning()) {
            AppState.setStatus("连接失败，请检查配置与日志");
            shutdown();
        }
    }

    private void startTun(int port) {
        if (stopping) {
            return;
        }

        try {
            Builder builder = new Builder();
            builder.setSession("AnyTLS");
            builder.setMtu(8500);
            builder.setBlocking(false);
            builder.addAddress("198.18.0.1", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addAddress("fc00::1", 128);
            builder.addRoute("::", 0);
            builder.addDnsServer("198.18.0.2");
            builder.addDisallowedApplication(getPackageName());

            ParcelFileDescriptor fd = builder.establish();
            if (fd == null) {
                throw new IllegalStateException("建立 VPN 接口失败（未授予 VPN 权限）");
            }
            tunFd = fd;

            File configFile = new File(getCacheDir(), "hev.conf");
            writeHevConfig(configFile, port);

            if (!TProxyService.TProxyStartService(configFile.getAbsolutePath(), fd.getFd())) {
                throw new IllegalStateException("tun2socks 启动失败");
            }

            AppState.setRunning(true);
            AppState.setStatus("已连接 · SOCKS5 127.0.0.1:" + port);
            AppState.addLog("VPN 已建立，全部应用流量将通过 AnyTLS 转发");
            updateNotification("已连接");
        } catch (Exception e) {
            AppState.addLog("启动失败: " + e.getMessage());
            AppState.setStatus("启动失败: " + e.getMessage());
            shutdown();
        }
    }

    private void writeHevConfig(File file, int socksPort) throws IOException {
        String config = "misc:\n"
                + "  task-stack-size: 81920\n"
                + "  log-level: warn\n"
                + "tunnel:\n"
                + "  mtu: 8500\n"
                + "  ipv4: 198.18.0.1\n"
                + "  ipv6: 'fc00::1'\n"
                + "  icmp: 'reply'\n"
                + "socks5:\n"
                + "  port: " + socksPort + "\n"
                + "  address: '127.0.0.1'\n"
                + "  udp: 'udp'\n"
                + "mapdns:\n"
                + "  address: 198.18.0.2\n"
                + "  port: 53\n"
                + "  network: 240.0.0.0\n"
                + "  netmask: 240.0.0.0\n"
                + "  cache-size: 10000\n";
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(config.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void shutdown() {
        if (stopping) {
            return;
        }
        stopping = true;
        AppState.addLog("正在断开…");

        new Thread(() -> {
            cleanupOnce();
            AppState.setRunning(false);
            AppState.setConnecting(false);
            AppState.setStatus("未连接");
            AppState.setTraffic(0, 0);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "anytls-stop").start();
    }

    private void cleanupOnce() {
        synchronized (cleanupLock) {
            if (cleaned) {
                return;
            }
            cleaned = true;
        }

        try {
            TProxyService.TProxyStopService();
        } catch (Throwable ignored) {
        }

        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }

        try {
            AnyTlsNative.stop();
        } catch (Throwable ignored) {
        }

        // In rare cases the user disconnects before the client registers its
        // cancellation token; retry briefly while the client is still starting.
        for (int attempt = 0; attempt < 3 && connecting; attempt++) {
            try {
                Thread.sleep(100);
                AnyTlsNative.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onRevoke() {
        shutdown();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        cleanupOnce();
        AppState.setRunning(false);
        AppState.setConnecting(false);
        if (!"未连接".equals(AppState.getStatus())) {
            AppState.setStatus("未连接");
        }
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, AnyTlsVpnService.class);
        stop.setAction(ACTION_DISCONNECT);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action stopAction = new Notification.Action.Builder(
                null, getString(R.string.notification_stop), stopPending).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_anytls)
                .setContentIntent(openPending)
                .addAction(stopAction)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
