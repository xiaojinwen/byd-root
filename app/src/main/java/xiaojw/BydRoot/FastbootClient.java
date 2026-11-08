package xiaojw.BydRoot;

import android.content.Context;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * fastboot 客户端（USB Host / bulk 端点），替代 PC 的 fastboot.exe。
 * 协议：命令一个 USB 包（不带 NUL），响应 OKAY / FAIL / INFO / TEXT / DATA 前缀。
 *
 * 对照 AOSP fastboot_driver.cpp 修订：
 *  - RawCommand 写 cmd.size() 字节，不加 '\0'（LFDeepFlash 加了 NUL，属多余字节）
 *  - HandleResponse 每收到 INFO/TEXT 会重置计时器，长耗时 flash 不能用固定总超时
 *  - DownloadCommand 用 download:%08x；SendBuffer 必须写满
 *  - 新增 RunAndReadBuffer（fetch/upload）用于 bootloader 层分区回读备份
 */
public final class FastbootClient implements Closeable {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int DATA_CHUNK = 16 * 1024;
    private static final int RESP_IDLE_MS = 30000;   // 无响应空闲超时
    private static final long RESP_HARD_LIMIT_MS = 30 * 60 * 1000L;

    private final UiBridge ui;
    private final UsbTransport.Bulk bulk;
    private final byte[] respBuf = new byte[512];

    private interface DataPhase {
        void send(long expectedBytes) throws IOException;
    }

    private FastbootClient(UiBridge ui, UsbTransport.Bulk bulk) {
        this.ui = ui;
        this.bulk = bulk;
    }

    public static FastbootClient connect(Context ctx, UiBridge ui, long timeoutMs)
            throws IOException, InterruptedException {
        return new FastbootClient(ui, UsbTransport.open(ctx, ui, UsbTransport.MODE_FASTBOOT, timeoutMs));
    }

    /** 发一条命令，循环消费 INFO/TEXT、处理 DATA，直到 OKAY/FAIL。 */
    private String transact(String cmd, DataPhase phase, long timeoutMs) throws IOException {
        writeCommand(cmd);
        return readResponseLoop(cmd, phase, null, null, timeoutMs, null);
    }

    private void writeCommand(String cmd) throws IOException {
        byte[] c = cmd.getBytes(ASCII);
        bulk.writeFully(c, 0, c.length, 5000);
    }

    /** AOSP HandleResponse：INFO/TEXT 重置空闲计时；DATA 后由 phase 发数据或由 sink 收数据。 */
    private String readResponseLoop(String cmd, DataPhase phase, OutputStream sink,
                                    ProgressSink cb, long timeoutMs, Map<String, String> vars)
            throws IOException {
        long hardDeadline = System.currentTimeMillis() + Math.max(timeoutMs, RESP_HARD_LIMIT_MS);
        long lastActivity = System.currentTimeMillis();
        boolean handledData = false;
        while (true) {
            if (ui.isCancelled()) throw new IOException("已取消");
            long now = System.currentTimeMillis();
            if (now > hardDeadline || now - lastActivity > Math.max(timeoutMs, RESP_IDLE_MS)) {
                throw new IOException("fastboot 响应超时: " + cmd);
            }
            int n = bulk.read(respBuf, 5000);
            if (n <= 0) continue;
            lastActivity = System.currentTimeMillis();
            String s = new String(respBuf, 0, n, ASCII);
            String tag = s.length() >= 4 ? s.substring(0, 4) : "";
            String payload = s.length() > 4 ? s.substring(4).trim() : "";
            if ("INFO".equals(tag)) {
                if (vars != null) {
                    String body = payload.startsWith("(bootloader) ")
                            ? payload.substring("(bootloader) ".length()) : payload;
                    int c = body.indexOf(':');
                    if (c > 0) vars.put(body.substring(0, c).trim(), body.substring(c + 1).trim());
                }
                ui.log("info", "(bootloader) " + payload);
                continue;
            }
            if ("TEXT".equals(tag)) { ui.log("info", payload); continue; }
            if ("FAIL".equals(tag)) throw new IOException("fastboot 失败: " + payload + " [" + cmd + "]");
            if ("DATA".equals(tag)) {
                if (handledData) throw new IOException("bootloader 重复返回 DATA [" + cmd + "]");
                long size;
                try { size = Long.parseLong(payload.replace("0x", ""), 16); }
                catch (NumberFormatException e) { throw new IOException("DATA 大小无法解析: " + s); }
                if (phase != null) {
                    phase.send(size);
                } else if (sink != null) {
                    readData(size, sink, cb);
                } else {
                    throw new IOException("bootloader 意外返回 DATA [" + cmd + "]");
                }
                handledData = true;
                lastActivity = System.currentTimeMillis();
                continue;
            }
            if ("OKAY".equals(tag)) return payload;
            ui.log("warn", "fastboot 未知响应: " + s.trim());
        }
    }

    /** AOSP RunAndReadBuffer 的 DATA 阶段：读满 N 字节原始流。 */
    private void readData(long size, OutputStream out, ProgressSink cb) throws IOException {
        byte[] buf = new byte[DATA_CHUNK];
        long done = 0;
        while (done < size) {
            if (ui.isCancelled()) throw new IOException("已取消");
            int want = (int) Math.min(buf.length, size - done);
            int n = bulk.readFully(buf, 0, want, 60000);
            if (n <= 0) throw new IOException("fastboot 数据流提前结束（已读 " + done + "/" + size + "）");
            out.write(buf, 0, n);
            done += n;
            if (cb != null) cb.onProgress(done, size);
        }
    }

    public String getVar(String name) throws IOException {
        return transact("getvar:" + name, null, 15000).trim();
    }

    /** 探测用：不支持的变量返回 null，不抛异常。 */
    public String tryGetVar(String name) {
        try { return getVar(name); } catch (Exception e) { return null; }
    }

    /**
     * getvar:all 一次性取回全部变量（含 partition-size:*），避免逐个探测上百个分区名。
     *  bootloader 用 INFO(bootloader) key: value 逐行返回。
     */
    public Map<String, String> getAllVars() throws IOException {
        Map<String, String> vars = new LinkedHashMap<String, String>();
        writeCommand("getvar:all");
        long last = System.currentTimeMillis();
        long hard = last + 60000;
        while (true) {
            long now = System.currentTimeMillis();
            if (now > hard || now - last > RESP_IDLE_MS) break;
            int n = bulk.read(respBuf, 5000);
            if (n <= 0) continue;
            last = System.currentTimeMillis();
            String s = new String(respBuf, 0, n, ASCII);
            String tag = s.length() >= 4 ? s.substring(0, 4) : "";
            String payload = s.length() > 4 ? s.substring(4).trim() : "";
            if ("INFO".equals(tag)) {
                String body = payload.startsWith("(bootloader) ")
                        ? payload.substring("(bootloader) ".length()) : payload;
                int c = body.indexOf(':');
                if (c > 0) vars.put(body.substring(0, c).trim(), body.substring(c + 1).trim());
            } else if ("FAIL".equals(tag) || "OKAY".equals(tag)) {
                break;
            }
        }
        return vars;
    }

    public long getVarLong(String name, long def) {
        return parseVarLong(tryGetVar(name), def);
    }

    /** bootloader 变量可能是 0x04000000 或十进制 67108864。 */
    public static long parseVarLong(String v, long def) {
        if (v == null || v.trim().isEmpty()) return def;
        try {
            String t = v.trim().toLowerCase(Locale.US);
            return t.startsWith("0x") ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
        } catch (NumberFormatException e) { return def; }
    }

    /** download:<size> + 数据流，对应 fastboot.exe 的镜像下发阶段。 */
    public void download(final InputStream in, final long size, final ProgressSink cb) throws IOException {
        long max = getVarLong("max-download-size", 0);
        if (max > 0) {
            ui.log("info", "bootloader max-download-size = " + max + "B");
            if (size > max) {
                throw new IOException("镜像 " + size + "B 超过 bootloader 上限 " + max
                        + "B（需要 sparse 分包，当前车机不支持）");
            }
        } else {
            ui.log("warn", "未取到 max-download-size，跳过上限检查");
        }

        final String cmd = "download:" + String.format(Locale.US, "%08x", size);
        ui.log("info", "开始下发镜像 " + size + " 字节（约 " + (size / 1024 / 1024) + "MB）…");
        transact(cmd, new DataPhase() {
            @Override
            public void send(long expected) throws IOException {
                byte[] buf = new byte[DATA_CHUNK];
                long done = 0;
                long lastLog = 0;
                while (done < expected) {
                    if (ui.isCancelled()) throw new IOException("已取消");
                    int want = (int) Math.min(buf.length, expected - done);
                    int n = in.read(buf, 0, want);
                    if (n <= 0) throw new IOException("镜像数据提前结束（已发 " + done + "B）");
                    bulk.writeFully(buf, 0, n, 30000);
                    done += n;
                    if (cb != null) cb.onProgress(done, expected);
                    if (done - lastLog >= 8 * 1024 * 1024) {
                        lastLog = done;
                        ui.log("info", "已下发 " + (done / 1024 / 1024) + "MB / "
                                + (expected / 1024 / 1024) + "MB");
                    }
                }
                if (cb != null) cb.onProgress(expected, expected);
            }
        }, 10 * 60 * 1000);
        ui.log("ok", "镜像下发完成");
    }

    /**
     * fastboot fetch:<partition>:0x<offset>:0x<size>，把分区内容回读到手机。
     * 仅较新 bootloader 支持（先探测 getvar:max-fetch-size）；用于系统起不来时的救砖备份。
     */
    public long fetch(String partition, long offset, long size, OutputStream out, ProgressSink cb)
            throws IOException {
        String cmd = "fetch:" + partition
                + String.format(Locale.US, ":0x%08x:0x%08x", offset, size);
        readResponseLoop(cmd, null, out, cb, 10 * 60 * 1000, null);
        return size;
    }

    /** fastboot boot <img>：临时启动，不写入分区（脚本的「挂载临时 Root」）。 */
    public void boot() throws IOException {
        try {
            transact("boot", null, 30000);
        } catch (IOException e) {
            // 部分 bootloader 发完 boot 就直接启动，不回 OKAY
            if (e.getMessage() != null && e.getMessage().contains("超时")) {
                ui.log("warn", "boot 未返回 OKAY（bootloader 直接启动），继续等待开机");
            } else {
                throw e;
            }
        }
        ui.log("ok", "临时 Root 启动命令已下发");
    }

    /** fastboot flash boot <img>：写入 boot 分区（脚本的「刷写长期 Root」）。 */
    public void flashBoot() throws IOException {
        transact("flash:boot", null, 5 * 60 * 1000);
        ui.log("ok", "长期 Root 刷写完成");
    }

    /** 按分区名回刷镜像（备份恢复用）。 */
    public void flash(String partition, InputStream in, long size, ProgressSink cb) throws IOException {
        download(in, size, cb);
        transact("flash:" + partition, null, 5 * 60 * 1000);
        ui.log("ok", "已回刷分区 " + partition);
    }

    public void reboot() {
        try { transact("reboot", null, 10000); }
        catch (Exception e) { ui.log("info", "reboot 后连接断开（正常）"); }
    }

    @Override
    public void close() { bulk.close(); }
}
