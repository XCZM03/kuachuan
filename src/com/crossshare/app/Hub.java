package com.crossshare.app;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** 同一进程内，页面与接收服务之间共享的轻量状态。 */
public class Hub {

    public static final Hub I = new Hub();

    public interface Listener {
        void onChange();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    public final List<String> logs = new ArrayList<String>();
    public final List<DeviceEntry> peers = new ArrayList<DeviceEntry>();

    public volatile boolean receiving = false;
    public volatile String localIp = "";
    public volatile String status = "未启动";
    public volatile int progress = 0;
    public volatile String lastApkUri = null;
    public volatile String lastApkName = null;

    public void setListener(Listener l) {
        listener = l;
    }

    public void fire() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            public void run() {
                l.onChange();
            }
        });
    }

    public void status(String s) {
        status = s;
        fire();
    }

    public void log(String s) {
        synchronized (logs) {
            logs.add(0, s);
            if (logs.size() > 60) logs.remove(logs.size() - 1);
        }
        fire();
    }
}
