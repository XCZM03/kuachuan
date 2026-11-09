package com.crossshare.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SendActivity.class));
            }
        });
        findViewById(R.id.btn_recv).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ReceiveActivity.class));
            }
        });
        findViewById(R.id.btn_hotspot).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.TETHER_SETTINGS"));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                    } catch (Exception e2) {
                        Toast.makeText(MainActivity.this, "请到系统设置里手动开启热点", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        final Button btnPerm = (Button) findViewById(R.id.btn_perm);
        final TextView tvPerm = (TextView) findViewById(R.id.tv_perm);
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            tvPerm.setText("尚未允许「安装未知应用」：收到 APK 后仍可在 下载/CrossShare 里点开安装，开启后能一键安装。");
            btnPerm.setVisibility(View.VISIBLE);
            btnPerm.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                }
            });
        } else {
            tvPerm.setText("已允许安装未知应用，收到 APK 后可直接点开安装。");
        }

        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[need.size()]), 200);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 26) {
            Button btnPerm = (Button) findViewById(R.id.btn_perm);
            TextView tvPerm = (TextView) findViewById(R.id.tv_perm);
            if (getPackageManager().canRequestPackageInstalls()) {
                btnPerm.setVisibility(View.GONE);
                tvPerm.setText("已允许安装未知应用，收到 APK 后可直接点开安装。");
            }
        }
    }
}
