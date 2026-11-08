package xiaojw.BydRoot;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android USB Host 传输层：手机作 host，车机作 device。
 * 同一套 bulk 通道分别承载 ADB(class ff/42/01) 与 fastboot(class ff/42/03)。
 *
 * 对照 AOSP client/transport_usb.cpp 与 LFDeepFlash/UsbConnection.kt 修订：
 *  - bulkTransfer 可能短写，所有写出统一走 writeFully 循环
 *  - 读取方向提供 readFully（fastboot DATA 阶段需要读满指定长度）
 *  - USB 拔出时不再在读取线程里空转死循环
 *  - 权限广播显式 setPackage，PendingIntent 用 FLAG_MUTABLE
 */
public final class UsbTransport {

    public static final String MODE_ADB = "adb";
    public static final String MODE_FASTBOOT = "fastboot";
    /** 高通 9008 EDL 深刷模式（Sahara + Firehose）。车机枚举为 VID 05C6 / PID 9008 等。 */
    public static final String MODE_EDL = "edl";
    private static final String ACTION_USB_PERMISSION = "xiaojw.BydRoot.USB_PERMISSION";
    /** Android usbfs 单次 URB 上限，ADB payload 按此切包。 */
    private static final int CHUNK = 16 * 1024;

    /** 高通 EDL 深刷常用 PID；部分机型重启进 Firehose 后会切换其中某个。 */
    private static final Set<Integer> EDL_PIDS = new HashSet<Integer>(Arrays.asList(
            0x9008, 0x9006, 0x900E, 0x901D));

    public static final class Target {
        UsbDevice device;
        UsbInterface intf;
        UsbEndpoint in, out;
        int maxPacket;
        String mode;

        public String describe() {
            return String.format(Locale.US, "vid=%04x pid=%04x %s iface=%d ep(in=%d,out=%d) mps=%d",
                    device.getVendorId(), device.getProductId(), mode,
                    intf.getId(), in.getAddress(), out.getAddress(), maxPacket);
        }
    }

    private static UsbManager manager(Context ctx) {
        return (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    /** 按模式找设备；fastboot 额外接受任意带双 bulk 端点的 vendor 接口（部分车机 bootloader 不标准）。 */
    public static Target find(Context ctx, String mode) {
        UsbManager mgr = manager(ctx);
        if (mgr == null) return null;
        HashMap<String, UsbDevice> devices = mgr.getDeviceList();
        // 9008 EDL：按 VID/PID 识别（接口类不固定），取首个带双 bulk 端点的接口。
        if (MODE_EDL.equals(mode)) {
            for (UsbDevice d : devices.values()) {
                if (d.getVendorId() != 0x05C6 || !EDL_PIDS.contains(d.getProductId())) continue;
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    Target t = makeTarget(d, d.getInterface(i), mode);
                    if (t != null) return t;
                }
            }
            return null;
        }
        for (UsbDevice d : devices.values()) {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                if (!modeMatches(intf, mode)) continue;
                Target t = makeTarget(d, intf, mode);
                if (t != null) return t;
            }
        }
        if (MODE_FASTBOOT.equals(mode)) {
            for (UsbDevice d : devices.values()) {
                // 兼容 VID/PID 命中但接口描述符不标准的 bootloader
                if (!knownFastbootVidPid(d)) continue;
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    Target t = makeTarget(d, d.getInterface(i), mode);
                    if (t != null) return t;
                }
            }
            for (UsbDevice d : devices.values()) {
                for (int i = 0; i < d.getInterfaceCount(); i++) {
                    UsbInterface intf = d.getInterface(i);
                    if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_VENDOR_SPEC) continue;
                    Target t = makeTarget(d, intf, mode);
                    if (t != null) return t;
                }
            }
        }
        return null;
    }

    private static boolean knownFastbootVidPid(UsbDevice d) {
        int vid = d.getVendorId(), pid = d.getProductId();
        return (vid == 0x18D1 && (pid == 0x4EE0 || pid == 0x4EE2 || pid == 0xD00D))
                || vid == 0x0BB4 || vid == 0x0502 || vid == 0x22B8;
    }

    private static boolean modeMatches(UsbInterface intf, String mode) {
        int cls = intf.getInterfaceClass();
        int sub = intf.getInterfaceSubclass();
        int proto = intf.getInterfaceProtocol();
        String name = intf.getName() == null ? "" : intf.getName().toLowerCase(Locale.US);
        if (cls != UsbConstants.USB_CLASS_VENDOR_SPEC || sub != 0x42) {
            return MODE_FASTBOOT.equals(mode) && name.contains("fastboot");
        }
        if (MODE_ADB.equals(mode)) return proto == 0x01 && !name.contains("fastboot");
        return proto == 0x03 || proto == 0x02 || name.contains("fastboot");
    }

    private static Target makeTarget(UsbDevice d, UsbInterface intf, String mode) {
        UsbEndpoint in = null, out = null;
        for (int j = 0; j < intf.getEndpointCount(); j++) {
            UsbEndpoint ep = intf.getEndpoint(j);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
            else out = ep;
        }
        if (in == null || out == null) return null;
        Target t = new Target();
        t.device = d;
        t.intf = intf;
        t.in = in;
        t.out = out;
        t.maxPacket = Math.max(64, Math.min(in.getMaxPacketSize(), out.getMaxPacketSize()));
        t.mode = mode;
        return t;
    }

    /** 轮询等待目标模式出现（设备从 ADB 切到 fastboot 会重新枚举），申请权限并声明接口。 */
    public static Bulk open(Context ctx, UiBridge ui, String mode, long timeoutMs)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean announced = false;
        while (System.currentTimeMillis() < deadline) {
            if (ui.isCancelled()) throw new IOException("已取消");
            Target t = find(ctx, mode);
            if (t != null) {
                UsbManager mgr = manager(ctx);
                if (!mgr.hasPermission(t.device)) {
                    ui.log("info", "正在申请 USB 权限，请在弹窗中勾选「一律使用此应用」");
                    if (!requestPermission(ctx, mgr, t.device, 60000)) {
                        throw new IOException("USB 权限被拒绝");
                    }
                }
                UsbDeviceConnection conn = mgr.openDevice(t.device);
                if (conn == null) throw new IOException("无法打开 USB 设备");
                if (!conn.claimInterface(t.intf, true)) {
                    conn.close();
                    throw new IOException("无法声明 USB 接口（被其他应用占用？）");
                }
                ui.log("ok", "已连接 " + t.describe());
                return new Bulk(conn, t);
            }
            if (!announced) {
                ui.log("info", "等待 " + mode + " 模式的车机接入 USB OTG…");
                announced = true;
            }
            Thread.sleep(1000);
        }
        throw new IOException("等待 " + mode + " 设备超时（" + timeoutMs / 1000 + "s）");
    }

    private static boolean requestPermission(Context ctx, UsbManager mgr, UsbDevice dev, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean granted = new AtomicBoolean(false);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (ACTION_USB_PERMISSION.equals(i.getAction())) {
                    granted.set(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                    latch.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
        // 显式限定包名，避免部分 ROM 把权限广播发给别的应用；MUTABLE 让系统能填入 EXTRA_PERMISSION_GRANTED
        PendingIntent pi = PendingIntent.getBroadcast(
                ctx, 0, new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName()),
                PendingIntent.FLAG_MUTABLE);
        try {
            mgr.requestPermission(dev, pi);
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            return granted.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            try { ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
    }

    /** 一条 USB bulk 通道。 */
    public static final class Bulk implements Closeable {
        private final UsbDeviceConnection conn;
        private final UsbInterface intf;
        private final UsbEndpoint in, out;
        public final int maxPacket;
        volatile boolean closed;

        Bulk(UsbDeviceConnection conn, Target t) {
            this.conn = conn;
            this.intf = t.intf;
            this.in = t.in;
            this.out = t.out;
            this.maxPacket = t.maxPacket;
        }

        public int read(byte[] buf, int timeoutMs) {
            return read(buf, 0, buf.length, timeoutMs);
        }

        public int read(byte[] buf, int off, int len, int timeoutMs) {
            if (closed) return -1;
            return conn.bulkTransfer(in, buf, off, len, timeoutMs);
        }

        /** 读满 len 字节；fastboot DATA 阶段的数据流用。返回实际读到的字节数。 */
        public int readFully(byte[] buf, int off, int len, int timeoutMs) throws IOException {
            int total = 0;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (total < len) {
                if (closed) throw new IOException("USB 已断开");
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                int n = conn.bulkTransfer(in, buf, off + total, len - total, (int) Math.min(remain, 5000));
                if (n < 0) throw new IOException("USB 读取失败（设备可能已拔出）");
                if (n == 0) continue;
                total += n;
            }
            return total;
        }

        /** bulkTransfer 允许短写，必须循环补足，否则大镜像会直接失败或写坏。 */
        public void writeFully(byte[] data, int off, int len, int timeoutMs) throws IOException {
            int sent = 0;
            while (sent < len) {
                if (closed) throw new IOException("USB 已断开");
                int want = Math.min(CHUNK, len - sent);
                int n;
                if (off + sent == 0 && want == data.length) {
                    n = conn.bulkTransfer(out, data, want, timeoutMs);
                } else {
                    n = conn.bulkTransfer(out, data, off + sent, want, timeoutMs);
                }
                if (n < 0) throw new IOException("USB 写入失败（设备可能已拔出）@" + sent);
                if (n == 0) throw new IOException("USB 写入超时 @" + sent);
                sent += n;
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try { conn.releaseInterface(intf); } catch (Exception ignored) { }
            conn.close();
        }
    }

    /**
     * ADB over USB 是「按包」而不是纯字节流：把收到的 USB 包拼成连续流给 AdbProtocol 解析。
     * AOSP remote_read 同样先读 24B header 再读 data_length，拼接是合法的。
     */
    public static final class AdbUsbInputStream extends InputStream {
        private final Bulk bulk;
        private final byte[] readBuf = new byte[16 * 1024 + 64];
        private int pos = 0, limit = 0;
        private int idleRounds = 0;

        AdbUsbInputStream(Bulk bulk) { this.bulk = bulk; }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= limit) {
                int n = bulk.read(readBuf, 2000);
                if (n > 0) { pos = 0; limit = n; idleRounds = 0; break; }
                if (bulk.closed) return -1;
                if (n < 0) throw new IOException("USB 设备已断开");
                // 连续空读约 60s（30 轮 × 2s），避免设备被拔出时线程空转烧 CPU
                if (++idleRounds > 30) throw new IOException("USB 读取超时，设备可能已断开");
            }
            int n = Math.min(len, limit - pos);
            System.arraycopy(readBuf, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == 1 ? (one[0] & 0xff) : -1;
        }

        @Override
        public void close() { bulk.close(); }
    }

    /**
     * ADB over USB 要求 header(24B) 与 payload 分别成包；AdbLib 每次 write 的是一条完整 ADB 消息，
     * 这里按 data_length 拆出完整消息后，header 单独一个包、payload 按 16KB 分包写出。
     * 对齐 AOSP UsbConnection::Write()：header 与 payload 两次独立写入。
     */
    public static final class AdbUsbOutputStream extends OutputStream {
        private final Bulk bulk;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        AdbUsbOutputStream(Bulk bulk) { this.bulk = bulk; }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            pending.write(b, off, len);
            drain();
        }

        @Override
        public void write(int b) throws IOException {
            pending.write(b);
            drain();
        }

        @Override
        public void flush() throws IOException { drain(); }

        private void drain() throws IOException {
            while (true) {
                byte[] data = pending.toByteArray();
                if (data.length < 24) return;
                long payloadLen = leInt(data, 12) & 0xffffffffL;
                if (payloadLen > 1024 * 1024) throw new IOException("非法 ADB 消息长度: " + payloadLen);
                long total = 24 + payloadLen;
                if (data.length < total) return;
                bulk.writeFully(data, 0, 24, 5000);
                if (payloadLen > 0) bulk.writeFully(data, 24, (int) payloadLen, 15000);
                int restLen = data.length - (int) total;
                if (restLen > 0) {
                    byte[] rest = new byte[restLen];
                    System.arraycopy(data, (int) total, rest, 0, restLen);
                    pending.reset();
                    pending.write(rest, 0, restLen);
                } else {
                    pending.reset();
                }
            }
        }

        private static long leInt(byte[] b, int off) {
            return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                    | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
        }

        @Override
        public void close() throws IOException { flush(); bulk.close(); }
    }

    private UsbTransport() { }
}
