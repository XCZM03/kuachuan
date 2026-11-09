package com.crossshare.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/** 后台监听 8765 端口，并把接收端广播到局域网，方便发送端自动发现。 */
public class ReceiveService extends Service {

    private static final String CHANNEL = "crossshare_recv";

    private ServerSocket server;
    private Thread loop;
    private volatile boolean running = false;
    private NsdManager nsd;
    private NsdManager.RegistrationListener reg;
    private WifiManager.MulticastLock multicast;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel(
                CHANNEL, "互传接收服务", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("保持局域网接收端口开启");
        nm.createNotificationChannel(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showForeground();
        if (!running) startEngine();
        return START_STICKY;
    }

    private void showForeground() {
        Intent i = new Intent(this, ReceiveActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("跨传 · 正在接收")
                .setContentText("地址 " + Io.localIp() + ":" + Io.PORT)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, 1); // 1 = FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            startForeground(1, n);
        }
    }

    private void startEngine() {
        try {
            server = new ServerSocket(Io.PORT, 8);
        } catch (Exception e) {
            Hub.I.log("端口 " + Io.PORT + " 被占用：" + e.getMessage());
            Hub.I.status("端口被占用，请关闭其它互传应用后重试");
            return;
        }
        running = true;
        Hub.I.receiving = true;
        Hub.I.localIp = Io.localIp();
        Hub.I.status("监听中 " + Hub.I.localIp + ":" + Io.PORT);
        Hub.I.log("接收已开启，等待对方连接…");
        registerNsd();
        loop = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "crossshare-accept");
        loop.start();
    }

    private void acceptLoop() {
        while (running && server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setSoTimeout(30000);
                Hub.I.log("有设备连入：" + s.getInetAddress().getHostAddress());
                try {
                    Io.RecvItem item = Io.receive(s, ReceiveService.this, null);
                    Hub.I.log("已收到 " + item.name + "（" + Io.human(item.size) + "）→ 下载/CrossShare");
                    if (item.apk) {
                        Hub.I.lastApkUri = item.uri.toString();
                        Hub.I.lastApkName = item.name;
                    }
                    Hub.I.fire();
                    notifyDone(item);
                } catch (Exception e) {
                    Hub.I.log("接收失败：" + e.getMessage());
                } finally {
                    try { s.close(); } catch (Exception ignore) {}
                }
            } catch (SocketException e) {
                if (running) Hub.I.log("监听中断：" + e.getMessage());
                break;
            } catch (Exception e) {
                if (running) Hub.I.log("连接错误：" + e.getMessage());
            }
        }
    }

    private void notifyDone(Io.RecvItem item) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification.Builder b = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("收到 " + item.name)
                    .setContentText(Io.human(item.size) + " · 已保存到 下载/CrossShare")
                    .setAutoCancel(true);
            if (item.apk) {
                Intent ins = Io.installIntent(this, item.uri);
                PendingIntent pi = PendingIntent.getActivity(this,
                        (int) (System.currentTimeMillis() & 0x7fffffff), ins,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(android.R.drawable.ic_menu_view, "安装", pi);
                b.setContentIntent(pi);
            }
            nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), b.build());
        } catch (Exception ignore) {
        }
    }

    private void registerNsd() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicast = wm.createMulticastLock("crossshare-recv");
            multicast.setReferenceCounted(true);
            multicast.acquire();
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            String model = Build.MODEL.replaceAll("[^A-Za-z0-9-]", "-");
            info.setServiceName("CrossShare-" + model + "-" + (System.currentTimeMillis() % 10000L));
            info.setServiceType(Io.SERVICE_TYPE);
            info.setPort(Io.PORT);
            reg = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo i) {
                    Hub.I.log("已广播到局域网，发送端可自动发现");
                }
                public void onRegistrationFailed(NsdServiceInfo i, int code) {
                    Hub.I.log("自动发现广播失败（不影响手动输 IP）：" + code);
                }
                public void onServiceUnregistered(NsdServiceInfo i) {}
                public void onUnregistrationFailed(NsdServiceInfo i, int code) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg);
        } catch (Exception e) {
            Hub.I.log("自动发现不可用：" + e.getMessage());
        }
    }

    private void stopEngine() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignore) {}
        try { if (nsd != null && reg != null) nsd.unregisterService(reg); } catch (Exception ignore) {}
        try { if (multicast != null && multicast.isHeld()) multicast.release(); } catch (Exception ignore) {}
        Hub.I.receiving = false;
        Hub.I.status("已停止");
        Hub.I.log("接收服务已停止");
    }

    @Override
    public void onDestroy() {
        stopEngine();
        try { stopForeground(true); } catch (Exception ignore) {}
        super.onDestroy();
    }
}
