package xiaojw.BydRoot;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高通 9008 EDL 深刷客户端（USB Host / bulk 端点），替代 PC 的 QFIL / LF深刷。
 *
 * 流程：Sahara 握手 → 上传 Firehose 编程器 → Command Mode → configure → program 各分区 → reset。
 * 协议字节布局与 LF深刷（QualcommEngine / SaharaProtocol / FirehoseProtocol）保持一致，
 * 以复用其已验证过的握手与 Firehose 帧格式。
 *
 * 与 FastbootClient 同构：所有 USB 读写走 UsbTransport.Bulk（writeFully 循环补包、
 * readFully 读满指定长度），全程检查 ui.isCancelled()，日志与进度经 UiBridge 回传。
 */
public final class EdlClient implements AutoCloseable {

    // ---- Sahara 命令 ----
    private static final long SAHARA_HELLO_CMD = 0x01;
    private static final long SAHARA_HELLO_RESP = 0x02;
    private static final long SAHARA_READ_DATA = 0x03;
    private static final long SAHARA_END_IMAGE_TX = 0x04;
    private static final long SAHARA_DONE = 0x05;
    private static final long SAHARA_DONE_RESP = 0x06;
    private static final long SAHARA_RESET = 0x07;
    private static final long SAHARA_CMD_READY = 0x0B;

    public static final long MODE_IMAGE_TX_PENDING = 0;
    private static final long MODE_COMMAND_MODE = 3;
    private static final long IMAGE_ID_NONE = 0xFFFFFFFEL;

    private static final int SAHARA_VERSION_MAJOR = 2;
    private static final int SAHARA_VERSION_MINOR = 1;

    private static final int TIMEOUT_HELLO = 5000;
    private static final int TIMEOUT_CMD = 5000;
    private static final int TIMEOUT_DATA = 8000;

    private static final int CHUNK_SIZE = 512 * 1024;        // Sahara 上传块
    private static final int FH_DEFAULT_PAYLOAD = 0x40000;   // 256KB 初始
    private static final int FH_MAX_PAYLOAD = 0x1000000;     // 16MB
    private static final int FH_RSP_TIMEOUT = 60000;

    /** 握手返回的模式；IMT_PENDING 表示需要上传 Firehose，否则已处于 Command Mode。 */
    public static final class HandshakeResult {
        public long mode;
    }

    private final Context ctx;
    private final UiBridge ui;
    private UsbTransport.Bulk bulk;
    private int maxPayload = FH_DEFAULT_PAYLOAD;
    private final byte[] rbuf = new byte[0x10000];

    private EdlClient(Context ctx, UiBridge ui) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
    }

    public static EdlClient connect(Context ctx, UiBridge ui, long timeoutMs)
            throws IOException, InterruptedException {
        EdlClient c = new EdlClient(ctx, ui);
        c.bulk = UsbTransport.open(ctx, ui, UsbTransport.MODE_EDL, timeoutMs);
        return c;
    }

    // ==================================================================
    // Sahara 握手
    // ==================================================================

    public HandshakeResult handshake() throws IOException {
        ui.log("info", "等待 Sahara Hello（车机需处于 9008 深刷模式）…");
        drain(100);

        RawCommand hello = readCommand(TIMEOUT_HELLO);
        if (hello.cmd != SAHARA_HELLO_CMD) {
            throw new IOException("握手失败：期望 Hello(0x01)，收到 0x" + Long.toHexString(hello.cmd));
        }
        ByteBuffer bb = ByteBuffer.wrap(hello.body).order(ByteOrder.LITTLE_ENDIAN);
        long version = bb.getInt() & 0xffffffffL;
        long compatible = bb.getInt() & 0xffffffffL;
        int maxLen = bb.getInt();
        long mode = bb.getInt() & 0xffffffffL;
        ui.log("info", "Hello: version=" + version + " compatible=" + compatible
                + " maxPayload=" + maxLen + " mode=0x" + Long.toHexString(mode));

        // Hello Response：标准 8 字节头（command + length）+ 版本/上限/mode（长度自洽，避免错位）。
        int outMax = Math.max(512, Math.min(maxLen, 0x100000));
        ByteBuffer resp = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        resp.putInt((int) SAHARA_HELLO_RESP);
        resp.putInt(32);
        resp.putInt(SAHARA_VERSION_MAJOR);
        resp.putInt(SAHARA_VERSION_MINOR);
        resp.putInt(outMax);
        resp.putInt((int) MODE_IMAGE_TX_PENDING);
        resp.putInt(1);
        resp.putInt(0);
        writeFully(resp.array(), 0, 32, TIMEOUT_CMD);

        RawCommand next = readCommand(TIMEOUT_CMD);
        HandshakeResult r = new HandshakeResult();
        if (next.cmd == SAHARA_READ_DATA) {
            // 设备请求发送 Firehose 镜像
            r.mode = MODE_IMAGE_TX_PENDING;
        } else if (next.cmd == SAHARA_CMD_READY) {
            // 设备已进入 Command Mode（Firehose 已就绪）
            r.mode = MODE_COMMAND_MODE;
        } else {
            throw new IOException("握手失败：期望 READ_DATA/CMD_READY，收到 0x"
                    + Long.toHexString(next.cmd));
        }
        return r;
    }

    /** 分块上传 Firehose 编程器（.mbn/.elf），每块等待 READ_DATA ACK。 */
    public void uploadFirehose(InputStream firehose, long size, ProgressSink cb) throws IOException {
        ui.log("info", "开始上传 Firehose: " + size + " 字节, 块大小 " + CHUNK_SIZE);
        long offset = 0;
        while (offset < size) {
            if (ui.isCancelled()) throw new IOException("已取消");
            int len = (int) Math.min(CHUNK_SIZE, size - offset);
            byte[] buf = new byte[len];
            int got = 0;
            while (got < len) {
                int r = firehose.read(buf, got, len - got);
                if (r < 0) throw new IOException("Firehose 文件读取结束（预期 " + size + "B）");
                got += r;
            }
            writeFully(buf, 0, len, TIMEOUT_DATA);
            offset += len;
            if (cb != null) cb.onProgress(offset, size);

            RawCommand ack = readCommand(TIMEOUT_DATA);
            if (ack.cmd == SAHARA_END_IMAGE_TX) {
                long err = (ack.body.length >= 4)
                        ? (ByteBuffer.wrap(ack.body).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL)
                        : -1;
                throw new IOException("Firehose 上传被设备拒绝（错误码 " + Long.toHexString(err) + "）");
            }
            if (ack.cmd != SAHARA_READ_DATA) {
                throw new IOException("上传 ACK 异常：期望 READ_DATA，收到 0x" + Long.toHexString(ack.cmd));
            }
            ByteBuffer ab = ByteBuffer.wrap(ack.body).order(ByteOrder.LITTLE_ENDIAN);
            int ackImageId = ab.getInt();
            int ackOffset = ab.getInt();
            int ackLen = ab.getInt();
            if (ackImageId != (int) IMAGE_ID_NONE || ackOffset != offset || ackLen != len) {
                throw new IOException("ACK 校验失败: id=" + ackImageId + " off=" + ackOffset
                        + " len=" + ackLen + "（期望 off=" + offset + " len=" + len + "）");
            }
        }
        ui.log("ok", "Firehose 上传完成");
    }

    /** 发送 END_IMAGE_TX 并读取应答。 */
    public void endTransfer(boolean success) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(SAHARA_END_IMAGE_TX);
        bb.putInt(8);
        bb.putInt(success ? 0 : 1);
        writeFully(bb.array(), 0, 16, TIMEOUT_CMD);
        RawCommand resp = readCommand(TIMEOUT_CMD);
        ui.log("info", "END_IMAGE_TX → 0x" + Long.toHexString(resp.cmd));
    }

    // ==================================================================
    // Firehose 命令模式
    // ==================================================================

    /**
     * 进入 Firehose 命令模式并执行 configure。
     * 上传完成后部分机型会重新枚举 USB，因此先在当前连接上试一次；若失败则关闭旧连接、
     * 重新打开 9008 设备再 configure（两种硬件行为都能兼容）。
     */
    public void enterFirehoseAndConfigure() throws Exception {
        ui.log("info", "Firehose 上传完成，进入 Firehose 命令模式…");
        Thread.sleep(2000);
        drain(300);
        try {
            configureAuto();
        } catch (IOException first) {
            ui.log("warn", "当前连接 configure 失败（" + first.getMessage()
                    + "），尝试重新打开 9008 设备…");
            try { if (bulk != null) bulk.close(); } catch (Exception ignored) { }
            bulk = null;
            bulk = UsbTransport.open(ctx, ui, UsbTransport.MODE_EDL, 20000);
            drain(300);
            configureAuto();
        }
    }

    /** AUTO：依次尝试 UFS → eMMC → NAND → SPI，直到 configure 被 ACK。 */
    private void configureAuto() throws IOException {
        String[] order = {"ufs", "emmc", "nand", "spi"};
        String picked = null;
        for (int i = 0; i < order.length; i++) {
            try {
                configure(order[i]);
                picked = order[i];
                break;
            } catch (IOException e) {
                if (i == order.length - 1) {
                    throw new IOException("configure 失败：UFS/eMMC/NAND/SPI 均未被接受，请检查 Firehose 是否匹配");
                }
                ui.log("warn", "configure MemoryName=" + order[i] + " 被拒绝，尝试下一个");
            }
        }
        ui.log("ok", "闪存类型：" + picked);
    }

    private void configure(String memoryName) throws IOException {
        ui.log("info", "发送 configure，MemoryName=" + memoryName + "，MaxPayload=" + maxPayload);
        sendXml(buildConfigure(memoryName));
        String rsp = readResponse();
        requireAck(rsp, "configure");
        Matcher m = Pattern.compile("MaxPayloadSizeToTargetInBytesSupported=\"(\\d+)\"").matcher(rsp);
        if (m.find()) {
            int sup = Integer.parseInt(m.group(1));
            if (sup > maxPayload) {
                maxPayload = Math.min(sup, FH_MAX_PAYLOAD);
                ui.log("info", "设备支持更大包，升级 MaxPayload=" + maxPayload + "，重新 configure");
                sendXml(buildConfigure(memoryName));
                requireAck(readResponse(), "configure");
            }
        }
    }

    private String buildConfigure(String memoryName) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><data>"
                + "<configure MemoryName=\"" + memoryName + "\" "
                + "MaxPayloadSizeFromTargetInBytes=\"" + maxPayload + "\" "
                + "MaxPayloadSizeToTargetInBytes=\"" + maxPayload + "\" "
                + "MaxPayloadSizeToTargetInBytesSupported=\"" + maxPayload + "\" "
                + "ValidatePayloadSize=\"false\" SkipStorageInit=\"false\" SkipWrite=\"false\" "
                + "AlwaysValidate=\"false\" DisableVerity=\"false\" verbatim=\"1\" />"
                + "</data>";
    }

    /** 刷写一个分区：先发 program 头，再分块下发镜像数据，最后等 ACK。 */
    public void program(EdlPackage.Program p, InputStream image, long size, ProgressSink cb)
            throws IOException {
        ui.log("info", "program LBA " + p.startLba + "（" + p.numLba + " 扇区, "
                + (size / 1024) + "KB）→ " + p.label);
        sendXml(buildProgram(p));
        int chunk = Math.max(4096, maxPayload - 512);   // 预留头部余量
        long written = 0;
        byte[] buf = new byte[chunk];
        while (written < size) {
            if (ui.isCancelled()) throw new IOException("已取消");
            int toRead = (int) Math.min(chunk, size - written);
            int got = 0;
            while (got < toRead) {
                int r = image.read(buf, got, toRead - got);
                if (r < 0) break;
                got += r;
            }
            if (got <= 0) break;
            writeFully(buf, 0, got, FH_RSP_TIMEOUT);
            written += got;
            if (cb != null) cb.onProgress(written, size);
        }
        requireAck(readResponse(), "program(" + p.label + ")");
    }

    private String buildProgram(EdlPackage.Program p) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><data>"
                + "<program SECTOR_SIZE_IN_BYTES=\"512\" num_partition_sectors=\"" + p.numLba + "\" "
                + "physical_partition_number=\"" + p.physicalPartition + "\" start_sector=\""
                + p.startLba + "\" /></data>";
    }

    /** 发送 reset 让设备重启（深刷结束后脱离 9008）。 */
    public void resetDevice() throws IOException {
        ui.log("info", "发送 reset 重启设备");
        sendXml("<?xml version=\"1.0\" encoding=\"UTF-8\" ?><data><power value=\"reset\"/></data>");
        try { readResponse(); } catch (Exception ignored) { }
    }

    // ==================================================================
    // 底层读写
    // ==================================================================

    private void sendXml(String xml) throws IOException {
        byte[] b = xml.getBytes(StandardCharsets.US_ASCII);
        writeFully(b, 0, b.length, FH_RSP_TIMEOUT);
    }

    /** 读取 Firehose 响应，直到出现 ACK 或 NAK（含超时）。 */
    private String readResponse() throws IOException {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + FH_RSP_TIMEOUT;
        boolean ack = false, nak = false;
        while (System.currentTimeMillis() < deadline && !ack && !nak) {
            if (ui.isCancelled()) throw new IOException("已取消");
            int n = bulk.read(rbuf, FH_RSP_TIMEOUT);
            if (n < 0) throw new IOException("USB 读取失败（设备可能已断开）");
            if (n == 0) continue;
            sb.append(new String(rbuf, 0, n, StandardCharsets.US_ASCII));
            String t = sb.toString().toLowerCase(Locale.US);
            if (t.contains("<response value=\"ack\"") || t.contains("value= \"ack\"")) ack = true;
            if (t.contains("<response value=\"nak\"") || t.contains("value=\"nak\"")) nak = true;
            if (ack) deadline = System.currentTimeMillis() + 300;   // ACK 后可能还有尾随日志
        }
        String result = sb.toString();
        if (nak) throw new IOException("设备返回 NAK（Firehose 拒绝命令）");
        if (!ack && result.trim().isEmpty()) throw new IOException("Firehose 响应超时", new Throwable());
        return result;
    }

    private void requireAck(String rsp, String op) throws IOException {
        String low = rsp.toLowerCase(Locale.US);
        boolean ack = low.contains("<response value=\"ack\"") || low.contains("value= \"ack\"");
        if (!ack) {
            boolean nak = low.contains("nak");
            throw new IOException("Firehose " + op + (nak ? " 被设备拒绝(NAK)" : " 未收到 ACK")
                    + "\n" + (rsp.length() > 300 ? rsp.substring(0, 300) : rsp));
        }
    }

    private void writeFully(byte[] data, int off, int len, int timeout) throws IOException {
        if (bulk == null) throw new IOException("USB 未连接", new Throwable());
        bulk.writeFully(data, off, len, timeout);
    }

    /** 读满 8 字节头部（cmd + length）后再读 body；Sahara 头部为 cmd(4) + length(4)。 */
    private RawCommand readCommand(int timeout) throws IOException {
        byte[] header = new byte[8];
        int n = bulk.readFully(header, 0, 8, timeout);
        if (n < 8) throw new IOException("读取 Sahara 包超时/断开（读 " + n + "/8）");
        ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long cmd = bb.getInt() & 0xffffffffL;
        int length = bb.getInt();
        if (length < 8 || length > 0x100000) throw new IOException("Sahara 包长度异常: " + length);
        int bodyLen = Math.max(0, length - 8);
        byte[] body = new byte[bodyLen];
        if (bodyLen > 0) {
            int m = bulk.readFully(body, 0, bodyLen, timeout);
            if (m < bodyLen) throw new IOException("Sahara 包体读取不完整");
        }
        RawCommand rc = new RawCommand();
        rc.cmd = cmd;
        rc.body = body;
        return rc;
    }

    private void drain(int timeout) throws IOException {
        byte[] b = new byte[64 * 1024];
        int guard = 0;
        while (guard++ < 64) {
            if (bulk == null) return;
            int n = bulk.read(b, timeout);
            if (n <= 0) break;
        }
    }

    private static final class RawCommand {
        long cmd;
        byte[] body;
    }

    @Override
    public void close() {
        if (bulk != null) bulk.close();
        bulk = null;
    }
}
