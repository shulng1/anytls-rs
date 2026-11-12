package com.anytls.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQUEST_VPN = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    private EditText etServer;
    private EditText etPort;
    private EditText etSocksPort;
    private EditText etPassword;
    private EditText etSni;
    private CheckBox cbInsecure;
    private Button btnConnect;
    private TextView tvStatus;
    private TextView tvTraffic;
    private TextView tvLog;
    private ScrollView svLog;

    private AppConfig config;
    private boolean pendingConnect;
    private int logVersion = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        config = new AppConfig(this);
        etServer = findViewById(R.id.et_server);
        etPort = findViewById(R.id.et_port);
        etSocksPort = findViewById(R.id.et_socks_port);
        etPassword = findViewById(R.id.et_password);
        etSni = findViewById(R.id.et_sni);
        cbInsecure = findViewById(R.id.cb_insecure);
        btnConnect = findViewById(R.id.btn_connect);
        tvStatus = findViewById(R.id.tv_status);
        tvTraffic = findViewById(R.id.tv_traffic);
        tvLog = findViewById(R.id.tv_log);
        svLog = findViewById(R.id.sv_log);

        etServer.setText(config.getServer());
        etPort.setText(String.valueOf(config.getPort()));
        etSocksPort.setText(String.valueOf(config.getSocksPort()));
        etPassword.setText(config.getPassword());
        etSni.setText(config.getSni());
        cbInsecure.setChecked(config.isInsecure());

        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onConnectClicked();
            }
        });

        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void onConnectClicked() {
        if (AppState.isRunning() || AppState.isConnecting()) {
            Intent stop = new Intent(this, AnyTlsVpnService.class);
            stop.setAction(AnyTlsVpnService.ACTION_DISCONNECT);
            startService(stop);
            AppState.setStatus("正在断开…");
            return;
        }

        if (!validateAndSave()) {
            return;
        }

        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            pendingConnect = true;
            startActivityForResult(prepare, REQUEST_VPN);
        } else {
            startTunnel();
        }
    }

    private boolean validateAndSave() {
        String server = etServer.getText().toString().trim();
        if (server.isEmpty()) {
            toast(getString(R.string.invalid_server));
            return false;
        }

        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast(getString(R.string.invalid_port));
            return false;
        }
        if (port < 1 || port > 65535) {
            toast(getString(R.string.invalid_port));
            return false;
        }

        int socksPort;
        try {
            socksPort = Integer.parseInt(etSocksPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast(getString(R.string.invalid_socks_port));
            return false;
        }
        if (socksPort < 1024 || socksPort > 65535) {
            toast(getString(R.string.invalid_socks_port));
            return false;
        }

        String password = etPassword.getText().toString();
        if (password.isEmpty()) {
            toast(getString(R.string.invalid_password));
            return false;
        }

        config.save(server, port, password, etSni.getText().toString().trim(), cbInsecure.isChecked(), socksPort);
        return true;
    }

    private void startTunnel() {
        Intent intent = new Intent(this, AnyTlsVpnService.class);
        intent.setAction(AnyTlsVpnService.ACTION_CONNECT);
        startForegroundService(intent);
        AppState.setConnecting(true);
        AppState.setStatus("正在连接…");
        refreshUi();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                if (pendingConnect) {
                    pendingConnect = false;
                    startTunnel();
                }
            } else {
                pendingConnect = false;
                toast(getString(R.string.vpn_permission_denied));
            }
        }
    }

    private void refreshUi() {
        boolean active = AppState.isRunning() || AppState.isConnecting();

        tvStatus.setText(AppState.getStatus());
        tvTraffic.setText(getString(R.string.traffic_value,
                AppConfig.formatBytes(AppState.getTx()),
                AppConfig.formatBytes(AppState.getRx())));
        btnConnect.setText(active ? R.string.disconnect : R.string.connect);

        etServer.setEnabled(!active);
        etPort.setEnabled(!active);
        etSocksPort.setEnabled(!active);
        etPassword.setEnabled(!active);
        etSni.setEnabled(!active);
        cbInsecure.setEnabled(!active);

        int version = AppState.getLogVersion();
        if (version != logVersion) {
            logVersion = version;
            tvLog.setText(AppState.getLogText());
            svLog.post(new Runnable() {
                @Override
                public void run() {
                    svLog.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
