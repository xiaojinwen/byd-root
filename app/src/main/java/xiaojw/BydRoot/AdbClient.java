package xiaojw.BydRoot;

import android.content.Context;
import android.util.Base64;

import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbStream;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * ADB 客户端：把 PC 脚本里的 adb shell / adb push / adb reboot bootloader 全部换成
 * 手机 app 内自实现的 ADB 协议（USB Host 或 WiFi TCP）。
 */
public final class AdbClient implements Closeable {

    private final Context ctx;
    private final UiBridge ui;
    private AdbConnection conn;
    private UsbTransport.Bulk bulk;
    private Socket socket;
    private int maxData = 65536;

    public AdbClient(Context ctx, UiBridge ui) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
    }

    /** 复用已建立的 ADB 连接（协议回环测试 / 外部连接注入），不触碰任何 Android API。 */
    public AdbClient(UiBridge ui, AdbConnection existing) {
        this.ctx = null;
        this.ui = ui;
        this.conn = existing;
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** RSA 密钥持久化在应用私有目录，车机授权一次后后续连接免弹窗。 */
    public static AdbCrypto loadCrypto(Context context) throws Exception {
        AdbBase64 b64 = new AdbBase64() {
            @Override
            public String encodeToString(byte[] data) {
                return Base64.encodeToString(data, Base64.NO_WRAP);
            }
        };
        File priv = new File(context.getFilesDir(), "adbkey.pk8");
        File pub = new File(context.getFilesDir(), "adbkey.x509");
        if (priv.exists() && pub.exists() && priv.length() > 0) {
            return AdbCrypto.loadAdbKeyPair(b64, priv, pub);
        }
        AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(b64);
        crypto.saveAdbKeyPair(priv, pub);
        return crypto;
    }

    private AdbConnection.ConnectionListener listener() {
        return new AdbConnection.ConnectionListener() {
            @Override
            public void onAuthRequested() {
                ui.log("warn", "车机弹出「允许 USB 调试」时请勾选「一律允许」，然后点确定");
                ui.setStatus("等待车机授权…");
            }
            @Override
            public void onConnected() {
                ui.log("ok", "ADB 已连接并完成 RSA 认证");
                ui.setStatus("ADB 已连接");
            }
        };
    }

    public void connectUsb(long timeoutMs) throws Exception {
        close();
        bulk = UsbTransport.open(ctx, ui, UsbTransport.MODE_ADB, timeoutMs);
        conn = AdbConnection.create(
                new UsbTransport.AdbUsbInputStream(bulk),
                new UsbTransport.AdbUsbOutputStream(bulk),
                loadCrypto(ctx));
        conn.setConnectionListener(listener());
        conn.connect(20000);
        maxData = Math.max(4096, Math.min(conn.getMaxData(), 256 * 1024));
    }

    public void connectTcp(String host, int port, long timeoutMs) throws Exception {
        close();
        ui.log("info", "连接 WiFi ADB " + host + ":" + port);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setSoTimeout(0);
        conn = AdbConnection.create(socket, loadCrypto(ctx));
        conn.setConnectionListener(listener());
        conn.connect(20000);
        maxData = Math.max(4096, Math.min(conn.getMaxData(), 256 * 1024));
    }

    public boolean isConnected() { return conn != null && conn.isConnected(); }

    /** 执行一条 adb shell 命令，收集 stdout；CLSE 即命令结束。 */
    public String shell(String cmd, long timeoutMs) throws Exception {
        if (conn == null) throw new IOException("ADB 未连接");
        AdbStream s = conn.open("shell:" + cmd, 20000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (true) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    ui.log("warn", "shell 输出等待超时：" + cmd);
                    break;
                }
                byte[] p;
                try { p = s.read(remain); } catch (IOException e) { break; }
                out.write(p);
            }
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
        return new String(out.toByteArray(), UTF8).replace("\r\n", "\n");
    }

    /**
     * 流式执行 shell 命令并把 stdout 原样写入 out，返回读取字节数。
     * 分区备份用：dd 输出可能是数 GB，不能像 shell() 那样全量收进内存。
     * 注意 shell: 服务在标准 adbd 上是 raw binary（无 PTY 转换）；
     * 若设备厂商做了 CRLF 改写，调用方通过「字节数 == 分区大小 + SHA256」双校验来拦截。
     */
    public long shellStream(String cmd, OutputStream out, long idleTimeoutMs) throws Exception {
        if (conn == null) throw new IOException("ADB 未连接");
        AdbStream s = conn.open("shell:" + cmd, 20000);
        long total = 0;
        long lastData = System.currentTimeMillis();
        long hardDeadline = lastData + 2 * 60 * 60 * 1000L;
        try {
            while (true) {
                if (ui.isCancelled()) throw new IOException("用户已停止");
                long now = System.currentTimeMillis();
                if (now > hardDeadline || now - lastData > idleTimeoutMs) {
                    ui.log("warn", "shell 数据流超时：" + cmd);
                    break;
                }
                byte[] p;
                try { p = s.read(Math.min(idleTimeoutMs, hardDeadline - now)); }
                catch (IOException e) { break; }   // CLSE 即命令结束
                if (p == null || p.length == 0) continue;
                out.write(p, 0, p.length);
                total += p.length;
                lastData = System.currentTimeMillis();
            }
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
        return total;
    }

    /** adb reboot bootloader 的标准服务；部分车机只认 shell reboot，做兜底。 */
    public void rebootBootloader() {
        try {
            AdbStream s = conn.open("reboot:bootloader", 1500);
            try { s.close(); } catch (Exception ignored) { }
        } catch (Exception e) {
            // adbd 常在回 OKAY 之前就重启，超时属正常；若服务不存在则用 shell 兜底
            try { shell("reboot bootloader", 3000); } catch (Exception ignored) { }
        }
        ui.log("info", "已发送 reboot bootloader，等待车机进入 fastboot…");
    }

    /** adb push 的等价实现：ADB sync 服务的 SEND/DATA/DONE 流程。 */
    public void push(InputStream in, long size, String remotePath, String mode, ProgressSink cb)
            throws Exception {
        if (conn == null) throw new IOException("ADB 未连接");
        AdbStream s = conn.open("sync:", 20000);
        ReplyReader rr = new ReplyReader(s);
        try {
            byte[] spec = (remotePath + "," + mode).getBytes(UTF8);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write("SEND".getBytes(ASCII));
            writeLE(head, spec.length);
            head.write(spec);
            s.write(head.toByteArray());

            int chunk = Math.min(64 * 1024 - 8, maxData - 8);
            if (chunk < 1024) chunk = 1024;
            byte[] buf = new byte[chunk];
            long done = 0;
            while (done < size) {
                int want = (int) Math.min(buf.length, size - done);
                int n = in.read(buf, 0, want);
                if (n <= 0) throw new IOException("文件读取提前结束");
                ByteArrayOutputStream frame = new ByteArrayOutputStream(n + 8);
                frame.write("DATA".getBytes(ASCII));
                writeLE(frame, n);
                frame.write(buf, 0, n);
                s.write(frame.toByteArray());
                done += n;
                if (cb != null) cb.onProgress(done, size);
            }

            ByteArrayOutputStream doneFrame = new ByteArrayOutputStream();
            doneFrame.write("DONE".getBytes(ASCII));
            writeLE(doneFrame, (int) (System.currentTimeMillis() / 1000));
            s.write(doneFrame.toByteArray());

            String tag = new String(rr.readExactly(4, 180000), ASCII);
            int len = rr.readIntLE(30000);
            byte[] payload = len > 0 ? rr.readExactly(len, 30000) : new byte[0];
            if ("FAIL".equals(tag)) {
                throw new IOException("推送失败: " + new String(payload, UTF8));
            }
            if (!"OKAY".equals(tag)) throw new IOException("未知 sync 响应: " + tag);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static final class ReplyReader {
        private final AdbStream stream;
        private byte[] buf = new byte[0];
        private int pos = 0;

        ReplyReader(AdbStream stream) { this.stream = stream; }

        byte[] readExactly(int n, long timeoutMs) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (out.size() < n) {
                if (pos >= buf.length) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) throw new IOException("sync 响应读取超时");
                    buf = stream.read(remain);
                    pos = 0;
                    continue;
                }
                int take = Math.min(n - out.size(), buf.length - pos);
                out.write(buf, pos, take);
                pos += take;
            }
            return out.toByteArray();
        }

        int readIntLE(long timeoutMs) throws Exception {
            byte[] b = readExactly(4, timeoutMs);
            return (b[0] & 0xff) | ((b[1] & 0xff) << 8) | ((b[2] & 0xff) << 16) | ((b[3] & 0xff) << 24);
        }
    }

    private static void writeLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    @Override
    public void close() {
        try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        conn = null;
        if (bulk != null) bulk.close();
        bulk = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        socket = null;
    }
}
