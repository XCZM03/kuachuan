package com.crossshare.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Locale;

/** 局域网传输协议与文件读写。协议：UTF魔数 / UTF文件名 / long大小 / UTF类型 / 原始字节 / 1字节回执。 */
public class Io {

    public static final int PORT = 8765;
    public static final String MAGIC = "CROSSHARE/1";
    public static final String SERVICE_TYPE = "_crossshare._tcp.";

    public interface Progress {
        void onProgress(int percent);
    }

    public static class RecvItem {
        public String name;
        public long size;
        public Uri uri;
        public boolean apk;
    }

    public static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.US);
                if (n.startsWith("rmnet") || n.startsWith("ccmni")
                        || n.startsWith("pdp") || n.startsWith("wwan")) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("eth")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback == null ? "" : fallback;
        } catch (Exception e) {
            return "";
        }
    }

    public static String safeName(String s) {
        if (s == null) return "file.bin";
        s = s.replace('\\', '_').replace('/', '_').replace('\n', '_').replace('\r', '_');
        s = s.replaceAll("[\\x00-\\x1f]", "_").trim();
        if (s.length() > 120) s = s.substring(s.length() - 120);
        return s.length() == 0 ? "file.bin" : s;
    }

    public static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    public static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    public static Intent installIntent(Context ctx, Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        return i;
    }

    public static String nameOf(Context ctx, Uri u) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && !c.isNull(i)) return c.getString(i);
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) {}
        }
        String last = u.getLastPathSegment();
        return last == null ? "file.bin" : last;
    }

    public static long sizeOf(Context ctx, Uri u) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) {}
        }
        return -1;
    }

    public static File copyToCache(Context ctx, Uri u) throws Exception {
        File f = new File(ctx.getCacheDir(), "send_" + System.currentTimeMillis() + ".tmp");
        InputStream in = ctx.getContentResolver().openInputStream(u);
        if (in == null) throw new IOException("打不开所选文件");
        OutputStream out = new FileOutputStream(f);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            try { out.close(); } catch (Exception ignore) {}
            try { in.close(); } catch (Exception ignore) {}
        }
        return f;
    }

    /** 发送端：阻塞调用，请放在子线程。 */
    public static void send(String host, int port, String name, long size, InputStream src, Progress cb) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(20000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream acks = new DataInputStream(s.getInputStream());
            out.writeUTF(MAGIC);
            out.writeUTF(name);
            out.writeLong(size);
            out.writeUTF(mimeOf(name));
            out.flush();

            int ack = acks.read();
            if (ack != 0) throw new IOException("接收端拒绝接收（代码 " + ack + "）");

            byte[] buf = new byte[65536];
            long done = 0;
            int last = -1;
            while (done < size) {
                int n = src.read(buf, 0, (int) Math.min(buf.length, size - done));
                if (n < 0) break;
                if (n == 0) continue;
                out.write(buf, 0, n);
                done += n;
                if (cb != null) {
                    int p = (int) (done * 100 / Math.max(size, 1));
                    if (p != last) {
                        last = p;
                        cb.onProgress(p);
                    }
                }
            }
            if (done != size) throw new IOException("文件内容读取不完整（" + done + "/" + size + "）");
            out.flush();
            int fin = acks.read();
            if (fin != 0) throw new IOException("接收端写入失败");
            if (cb != null) cb.onProgress(100);
        } finally {
            try { s.close(); } catch (Exception ignore) {}
        }
    }

    private static class Target {
        OutputStream out;
        Uri uri;
        Uri pending;
        String name;
    }

    /** 只在 Android 10+ 加载，避免旧系统解析 MediaStore.Downloads。 */
    private static class Q {
        static Target create(Context ctx, String name, String mime) throws Exception {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CrossShare");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new IOException("无法创建下载记录");
            OutputStream os = ctx.getContentResolver().openOutputStream(u);
            if (os == null) throw new IOException("无法写入下载目录");
            Target t = new Target();
            t.out = os;
            t.uri = u;
            t.pending = u;
            t.name = name;
            return t;
        }

        static void finish(Context ctx, Uri u) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { ctx.getContentResolver().update(u, cv, null, null); } catch (Exception ignore) {}
        }
    }

    private static Target target(Context ctx, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) return Q.create(ctx, name, mime);
        Target t = new Target();
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CrossShare");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir);
        File f = new File(dir, name);
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ext);
        t.out = new FileOutputStream(f);
        t.uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(ctx.getPackageName() + ".files")
                .path(f.getAbsolutePath())
                .build();
        t.name = f.getName();
        return t;
    }

    /** 接收端：处理一条连接，返回落盘结果。 */
    public static RecvItem receive(Socket s, Context ctx, Progress cb) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        DataOutputStream out = new DataOutputStream(s.getOutputStream());

        String magic = in.readUTF();
        if (!MAGIC.equals(magic)) {
            out.write(2);
            out.flush();
            throw new IOException("协议不匹配");
        }
        String name = safeName(in.readUTF());
        long size = in.readLong();
        String mime = in.readUTF();
        if (size < 0 || size > 4L * 1024 * 1024 * 1024) {
            out.write(3);
            out.flush();
            throw new IOException("文件大小异常");
        }

        Target t = target(ctx, name, mime);
        RecvItem item = new RecvItem();
        item.name = t.name;
        item.size = size;
        item.uri = t.uri;
        item.apk = t.name.toLowerCase(Locale.US).endsWith(".apk");

        try {
            out.write(0);
            out.flush();
            byte[] buf = new byte[65536];
            long left = size;
            int last = -1;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException("传输中断");
                if (n == 0) continue;
                t.out.write(buf, 0, n);
                left -= n;
                if (cb != null) {
                    int p = (int) ((size - left) * 100 / Math.max(size, 1));
                    if (p != last) {
                        last = p;
                        cb.onProgress(p);
                    }
                }
            }
            t.out.flush();
            out.write(0);
            out.flush();
        } finally {
            try { t.out.close(); } catch (Exception ignore) {}
            if (t.pending != null && Build.VERSION.SDK_INT >= 29) Q.finish(ctx, t.pending);
        }
        return item;
    }
}
