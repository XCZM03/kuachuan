package com.crossshare.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class SendActivity extends Activity {

    private EditText etIp;
    private Button btnSend;
    private Button btnPick;
    private Button btnApps;
    private Button btnRefresh;
    private TextView tvSelected;
    private TextView tvStatus;
    private ProgressBar progress;
    private ListView lvDevices;
    private ListView lvApps;
    private BaseAdapter devAdapter;
    private BaseAdapter appAdapter;

    private final List<AppEntry> apps = new ArrayList<AppEntry>();
    private final LinkedList<NsdServiceInfo> resolveQueue = new LinkedList<NsdServiceInfo>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private NsdManager nsd;
    private NsdManager.DiscoveryListener disc;
    private WifiManager.MulticastLock lock;
    private boolean resolving = false;
    private boolean discoveryActive = false;

    private boolean hasFile = false;
    private String pendingName;
    private long pendingSize;
    private Uri pendingUri;
    private File pendingFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);

        etIp = (EditText) findViewById(R.id.et_ip);
        btnSend = (Button) findViewById(R.id.btn_send);
        btnPick = (Button) findViewById(R.id.btn_pick);
        btnApps = (Button) findViewById(R.id.btn_apps);
        btnRefresh = (Button) findViewById(R.id.btn_refresh);
        tvSelected = (TextView) findViewById(R.id.tv_selected);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        progress = (ProgressBar) findViewById(R.id.progress);
        lvDevices = (ListView) findViewById(R.id.lv_devices);
        lvApps = (ListView) findViewById(R.id.lv_apps);

        devAdapter = new BaseAdapter() {
            public int getCount() { return Hub.I.peers.size(); }
            public Object getItem(int p) { return Hub.I.peers.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_device, parent, false);
                DeviceEntry d = Hub.I.peers.get(p);
                ((TextView) v.findViewById(R.id.tv_name)).setText(pretty(d.name));
                ((TextView) v.findViewById(R.id.tv_addr)).setText(d.host + ":" + d.port + "  ·  点一下填入并发送");
                return v;
            }
        };
        appAdapter = new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int p) { return apps.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View v, ViewGroup parent) {
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_app, parent, false);
                AppEntry a = apps.get(p);
                ImageView iv = (ImageView) v.findViewById(R.id.iv_icon);
                iv.setImageDrawable(a.icon == null
                        ? getDrawable(android.R.drawable.sym_def_app_icon) : a.icon);
                ((TextView) v.findViewById(R.id.tv_name)).setText(a.label);
                ((TextView) v.findViewById(R.id.tv_meta)).setText(
                        a.pkg + "  ·  " + Io.human(a.size) + (a.version.length() > 0 ? "  ·  v" + a.version : ""));
                return v;
            }
        };
        lvDevices.setAdapter(devAdapter);
        lvApps.setAdapter(appAdapter);

        lvDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                DeviceEntry d = Hub.I.peers.get(pos);
                etIp.setText(d.host);
                tvStatus.setText("已选中 " + pretty(d.name) + "，可点「发送」");
                if (hasFile) doSend(d.host, d.port);
                else toast("地址已填好，再选一个文件或应用就能发");
            }
        });

        lvApps.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                AppEntry a = apps.get(pos);
                File f = new File(a.path);
                if (!f.canRead()) {
                    toast("读不到这个应用的安装包");
                    return;
                }
                pendingUri = null;
                pendingFile = f;
                pendingName = Io.safeName(a.label + "_" + a.version + ".apk");
                pendingSize = f.length();
                hasFile = true;
                tvSelected.setText("已选：" + a.label + " 的安装包（" + Io.human(pendingSize) + "）");
                tvStatus.setText("填好接收端 IP，或点上面发现的接收端，即可发送");
            }
        });

        btnPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFile(); }
        });
        btnApps.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (lvApps.getVisibility() == View.VISIBLE) {
                    lvApps.setVisibility(View.GONE);
                    btnApps.setText("已装应用（APK）");
                } else {
                    lvApps.setVisibility(View.VISIBLE);
                    btnApps.setText("收起应用列表");
                    if (apps.isEmpty()) loadApps();
                }
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { restartDiscovery(); }
        });
        btnSend.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String h = etIp.getText().toString().trim();
                if (h.length() == 0 && Hub.I.peers.size() == 1) h = Hub.I.peers.get(0).host;
                doSend(h, Io.PORT);
            }
        });

        loadApps();
        startDiscovery();
    }

    public void goBack(View v) {
        finish();
    }

    private void toast(String s) {
        Toast.makeText(SendActivity.this, s, Toast.LENGTH_SHORT).show();
    }

    private String pretty(String raw) {
        String s = raw;
        if (s.startsWith("CrossShare-")) s = s.substring(11);
        int k = s.lastIndexOf('-');
        if (k > 0 && s.substring(k + 1).matches("\\d+")) s = s.substring(0, k);
        return s.replace('-', ' ');
    }

    private boolean hasPeer(String raw) {
        for (int i = 0; i < Hub.I.peers.size(); i++) {
            if (Hub.I.peers.get(i).name.equals(raw)) return true;
        }
        return false;
    }

    private void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                final List<AppEntry> result = new ArrayList<AppEntry>();
                try {
                    PackageManager pm = getPackageManager();
                    List<PackageInfo> pis = pm.getInstalledPackages(0);
                    for (PackageInfo pi : pis) {
                        try {
                            ApplicationInfo ai = pi.applicationInfo;
                            if (ai == null || ai.sourceDir == null) continue;
                            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            boolean upd = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                            if (sys && !upd) continue;
                            File f = new File(ai.sourceDir);
                            if (!f.canRead() || f.length() == 0) continue;
                            AppEntry e = new AppEntry();
                            e.label = ai.loadLabel(pm).toString();
                            e.pkg = pi.packageName;
                            e.path = ai.sourceDir;
                            e.version = pi.versionName == null ? "" : pi.versionName;
                            e.size = f.length();
                            try { e.icon = ai.loadIcon(pm); } catch (Exception ignore) {}
                            result.add(e);
                        } catch (Exception ignore) {}
                    }
                    Collections.sort(result, new Comparator<AppEntry>() {
                        public int compare(AppEntry a, AppEntry b) {
                            return a.label.compareToIgnoreCase(b.label);
                        }
                    });
                } catch (Exception ignore) {}
                ui.post(new Runnable() {
                    public void run() {
                        apps.clear();
                        apps.addAll(result);
                        appAdapter.notifyDataSetChanged();
                        tvStatus.setText("已加载 " + apps.size() + " 个可发送的应用安装包");
                    }
                });
            }
        }, "crossshare-apps").start();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, 1001);
        } catch (Exception e) {
            Intent j = new Intent(Intent.ACTION_GET_CONTENT);
            j.addCategory(Intent.CATEGORY_OPENABLE);
            j.setType("*/*");
            try {
                startActivityForResult(j, 1001);
            } catch (Exception e2) {
                toast("找不到系统文件选择器");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 1001 || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {}
        try {
            String nm = Io.safeName(Io.nameOf(this, u));
            long sz = Io.sizeOf(this, u);
            File f = null;
            if (sz <= 0) {
                f = Io.copyToCache(this, u);
                sz = f.length();
            }
            pendingUri = u;
            pendingFile = f;
            pendingName = nm;
            pendingSize = sz;
            hasFile = true;
            tvSelected.setText("已选：" + nm + "（" + Io.human(sz) + "）");
            tvStatus.setText("填好接收端 IP 后点「发送」");
        } catch (Exception e) {
            toast("读取文件失败：" + e.getMessage());
        }
    }

    private void doSend(String host, final int port) {
        if (!hasFile) {
            toast("先选文件，或在「已装应用」里点一个应用");
            return;
        }
        if (host == null || host.length() == 0) {
            toast("请填接收端 IP，或点一下上面发现的接收端");
            return;
        }
        final String h = host.replaceAll("\\s+", "");
        final String nm = pendingName;
        final long sz = pendingSize;
        final File f = pendingFile;
        final Uri u = pendingUri;

        btnSend.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        tvStatus.setText("正在连接 " + h + ":" + port + " …");

        new Thread(new Runnable() {
            public void run() {
                InputStream in = null;
                try {
                    if (f != null) in = new FileInputStream(f);
                    else in = getContentResolver().openInputStream(u);
                    if (in == null) throw new IOException("打不开所选文件");
                    Io.send(h, port, nm, sz, in, new Io.Progress() {
                        public void onProgress(final int p) {
                            ui.post(new Runnable() {
                                public void run() {
                                    progress.setProgress(p);
                                    tvStatus.setText("发送中 " + p + "% · " + nm);
                                }
                            });
                        }
                    });
                    ui.post(new Runnable() {
                        public void run() {
                            tvStatus.setText("发送完成：" + nm + "（" + Io.human(sz) + "）");
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        public void run() {
                            String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                            tvStatus.setText("发送失败：" + m);
                            toast("发送失败：" + m);
                        }
                    });
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignore) {}
                    ui.post(new Runnable() {
                        public void run() {
                            btnSend.setEnabled(true);
                            progress.setVisibility(View.GONE);
                        }
                    });
                }
            }
        }, "crossshare-send").start();
    }

    private void startDiscovery() {
        if (discoveryActive) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (lock == null) {
                lock = wm.createMulticastLock("crossshare-send");
                lock.setReferenceCounted(true);
            }
            if (!lock.isHeld()) lock.acquire();
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            disc = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) {
                    ui.post(new Runnable() {
                        public void run() { tvStatus.setText("正在搜索附近的接收端…"); }
                    });
                }
                public void onServiceFound(final NsdServiceInfo si) {
                    ui.post(new Runnable() {
                        public void run() {
                            resolveQueue.add(si);
                            resolveNext();
                        }
                    });
                }
                public void onServiceLost(final NsdServiceInfo si) {
                    ui.post(new Runnable() {
                        public void run() {
                            String n = si.getServiceName();
                            for (int i = 0; i < Hub.I.peers.size(); i++) {
                                if (Hub.I.peers.get(i).name.equals(n)) {
                                    Hub.I.peers.remove(i);
                                    devAdapter.notifyDataSetChanged();
                                    break;
                                }
                            }
                        }
                    });
                }
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t, final int code) {
                    ui.post(new Runnable() {
                        public void run() {
                            tvStatus.setText("自动发现启动失败，直接填 IP 也能传（" + code + "）");
                        }
                    });
                }
                public void onStopDiscoveryFailed(String t, int code) {}
            };
            nsd.discoverServices(Io.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc);
            discoveryActive = true;
        } catch (Exception e) {
            tvStatus.setText("自动发现不可用，直接填 IP 也能传");
        }
    }

    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsd == null) return;
        resolving = true;
        final NsdServiceInfo si = resolveQueue.removeFirst();
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int code) {
                    ui.post(new Runnable() {
                        public void run() { resolving = false; resolveNext(); }
                    });
                }
                public void onServiceResolved(final NsdServiceInfo s) {
                    ui.post(new Runnable() {
                        public void run() {
                            try {
                                String host = s.getHost() == null ? null : s.getHost().getHostAddress();
                                int port = s.getPort();
                                String raw = s.getServiceName() == null ? "接收端" : s.getServiceName();
                                if (host != null && port > 0 && !hasPeer(raw)) {
                                    Hub.I.peers.add(new DeviceEntry(raw, host, port));
                                    devAdapter.notifyDataSetChanged();
                                }
                            } catch (Exception ignore) {}
                            resolving = false;
                            resolveNext();
                        }
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
            resolveNext();
        }
    }

    private void restartDiscovery() {
        stopDiscovery();
        tvStatus.setText("正在重新搜索…");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing()) startDiscovery();
            }
        }, 800);
    }

    private void stopDiscovery() {
        try {
            if (nsd != null && disc != null && discoveryActive) nsd.stopServiceDiscovery(disc);
        } catch (Exception ignore) {}
        discoveryActive = false;
        resolveQueue.clear();
        Hub.I.peers.clear();
        devAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Exception ignore) {}
        super.onDestroy();
    }
}
