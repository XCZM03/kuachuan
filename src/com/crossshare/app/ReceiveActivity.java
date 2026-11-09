package com.crossshare.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class ReceiveActivity extends Activity implements Hub.Listener {

    private TextView tvIp;
    private TextView tvPort;
    private TextView tvStatus;
    private Button btnToggle;
    private Button btnInstall;
    private ListView lvLogs;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);
        tvIp = (TextView) findViewById(R.id.tv_ip);
        tvPort = (TextView) findViewById(R.id.tv_port);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        btnToggle = (Button) findViewById(R.id.btn_toggle);
        btnInstall = (Button) findViewById(R.id.btn_install);
        lvLogs = (ListView) findViewById(R.id.lv_logs);

        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>());
        lvLogs.setAdapter(adapter);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(ReceiveActivity.this, ReceiveService.class);
                if (Hub.I.receiving) {
                    stopService(i);
                    Hub.I.status("已停止");
                } else {
                    startForegroundService(i);
                    Hub.I.status("正在启动接收…");
                }
                onChange();
            }
        });

        findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String ip = Io.localIp();
                if (ip.length() == 0) {
                    Toast.makeText(ReceiveActivity.this, "还没连上 Wi‑Fi 或热点", Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("ip", ip));
                Toast.makeText(ReceiveActivity.this, "已复制 " + ip, Toast.LENGTH_SHORT).show();
            }
        });

        btnInstall.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Hub.I.lastApkUri == null) return;
                try {
                    startActivity(Io.installIntent(ReceiveActivity.this, Uri.parse(Hub.I.lastApkUri)));
                } catch (Exception e) {
                    Toast.makeText(ReceiveActivity.this,
                            "无法调起安装器，请到系统设置里允许「安装未知应用」",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    public void goBack(View v) {
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.I.setListener(this);
        onChange();
    }

    @Override
    protected void onPause() {
        Hub.I.setListener(null);
        super.onPause();
    }

    public void onChange() {
        String ip = Io.localIp();
        tvIp.setText(ip.length() == 0 ? "未连接 Wi‑Fi" : ip);
        tvPort.setText("端口 " + Io.PORT + " · 保存到 下载/CrossShare");
        tvStatus.setText(Hub.I.status);
        btnToggle.setText(Hub.I.receiving ? "停止接收" : "开始接收");
        ArrayList<String> snapshot;
        synchronized (Hub.I.logs) {
            snapshot = new ArrayList<String>(Hub.I.logs);
        }
        adapter.clear();
        adapter.addAll(snapshot);
        adapter.notifyDataSetChanged();
        boolean has = Hub.I.lastApkUri != null;
        btnInstall.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) btnInstall.setText("安装最近收到的 " + Hub.I.lastApkName);
    }
}
