package com.cgutman.adblib;

import xiaojw.BydRoot.AdbClient;
import xiaojw.BydRoot.BackupManifest;
import xiaojw.BydRoot.ProgressSink;
import xiaojw.BydRoot.UiBridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * ADB 协议级回环测试：用纯 JVM 模拟一个 adbd，与真实 AdbClient/AdbConnection 代码全双工通信。
 * 覆盖 CNXN → AUTH TOKEN → SIGNATURE → RSA_PUBLIC → CNXN 授权握手、
 * shell: 二进制流（含 0x00/0x0A/0x0D/0xFF）、sync: SEND/DATA/DONE push。
 * 这是对「ADB 功能是否正常」最直接的验证（不依赖真机 USB）。
 */
public class LoopbackTest {

    static int pass = 0, fail = 0;
    static final Charset UTF8 = Charset.forName("UTF-8");

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + " -> " + detail); }
    }

    static final class NoUi implements UiBridge {
        @Override public void log(String level, String msg) { }
        @Override public void setStatus(String text) { }
        @Override public void setProgress(int percent, String text) { }
        @Override public void setBusy(boolean busy) { }
        @Override public boolean awaitConfirm(String title, String message) { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean onAutoBackup() { return true; }
    }

    static final class Sink implements ProgressSink {
        @Override public void onProgress(long done, long total) { }
        @Override public void onMessage(String msg) { }
    }

    /** 模拟 adbd：按 AOSP 消息语义回应 host。 */
    static final class MockAdbd extends Thread {
        final InputStream in;
        final OutputStream out;
        final byte[] shellData;
        volatile boolean signatureSeen = false;
        volatile boolean pubkeyValid = false;
        volatile boolean connected = false;
        volatile int openCount = 0;
        volatile String lastDest = "";
        final ByteArrayOutputStream pushed = new ByteArrayOutputStream();
        volatile boolean syncDone = false;

        MockAdbd(InputStream in, OutputStream out, byte[] shellData) {
            this.in = in;
            this.out = out;
            this.shellData = shellData;
            setDaemon(true);
        }

        private void send(byte[] packet) throws IOException {
            out.write(packet);
            out.flush();
        }

        @Override public void run() {
            final byte[] token = new byte[20];
            new Random(7).nextBytes(token);
            try {
                while (!isInterrupted()) {
                    AdbProtocol.AdbMessage m = AdbProtocol.AdbMessage.parseAdbMessage(in);
                    if (!AdbProtocol.validateMessage(m)) continue;
                    switch (m.command) {
                        case AdbProtocol.CMD_CNXN:
                            // 设备要求 RSA 认证
                            send(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_TOKEN, token));
                            break;
                        case AdbProtocol.CMD_AUTH:
                            if (m.arg0 == AdbProtocol.AUTH_TYPE_SIGNATURE) {
                                signatureSeen = m.payload != null && m.payload.length == 256;
                                // 模拟「签名不在已知密钥里」，再发一次 TOKEN 迫使 host 送公钥
                                send(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_TOKEN, token));
                            } else if (m.arg0 == AdbProtocol.AUTH_TYPE_RSA_PUBLIC) {
                                String s = new String(m.payload, UTF8);
                                try {
                                    int sp = s.indexOf(' ');
                                    byte[] raw = Base64.getDecoder().decode(
                                            sp > 0 ? s.substring(0, sp) : s.trim());
                                    pubkeyValid = raw.length == 524 && s.endsWith("\0");
                                } catch (Exception e) { pubkeyValid = false; }
                                send(AdbProtocol.generateMessage(AdbProtocol.CMD_CNXN,
                                        AdbProtocol.CONNECT_VERSION, AdbProtocol.CONNECT_MAXDATA,
                                        "device::\0".getBytes(UTF8)));
                                connected = true;
                            }
                            break;
                        case AdbProtocol.CMD_OPEN: {
                            openCount++;
                            int local = m.arg0;
                            int remote = 1000 + local;
                            String dest = new String(m.payload, UTF8);
                            int z = dest.indexOf('\0');
                            if (z >= 0) dest = dest.substring(0, z);
                            lastDest = dest;
                            // OKAY: arg0=remote id, arg1=local id（host 用 arg1 找 stream）
                            send(AdbProtocol.generateReady(remote, local));
                            if (dest.startsWith("shell:")) {
                                byte[] payload = dest.contains("dd if=")
                                        ? shellData : "hello\n".getBytes(UTF8);
                                send(AdbProtocol.generateWrite(remote, local, payload));
                                send(AdbProtocol.generateClose(remote, local));
                            }
                            break;
                        }
                        case AdbProtocol.CMD_WRTE: {
                            // host WRTE: arg0 = host local id, arg1 = device id
                            int hostLocal = m.arg0;
                            int deviceId = m.arg1;
                            // OKAY / WRTE 从 device 视角发：arg0=device id, arg1=host local id
                            send(AdbProtocol.generateReady(deviceId, hostLocal));
                            byte[] p = m.payload == null ? new byte[0] : m.payload;
                            if (p.length >= 4) {
                                String tag = new String(p, 0, 4, "US-ASCII");
                                if ("DATA".equals(tag)) {
                                    int len = le(p, 4);
                                    pushed.write(p, 8, len);
                                } else if ("DONE".equals(tag)) {
                                    syncDone = true;
                                    ByteArrayOutputStream rsp = new ByteArrayOutputStream();
                                    rsp.write("OKAY".getBytes("US-ASCII"));
                                    rsp.write(new byte[4]);   // length = 0
                                    send(AdbProtocol.generateWrite(deviceId, hostLocal,
                                            rsp.toByteArray()));
                                }
                            }
                            break;
                        }
                        case AdbProtocol.CMD_CLSE:
                        case AdbProtocol.CMD_OKAY:
                        default:
                            break;
                    }
                }
            } catch (IOException e) {
                // 测试结束、管道关闭
            } catch (Exception e) {
                System.out.println("  [mock adbd] " + e);
            }
        }

        static int le(byte[] b, int o) {
            return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8)
                    | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n[8] ADB 协议回环（模拟 adbd 全双工）");

        // 二进制载荷刻意包含易被 PTY/文本改写破坏的字节
        byte[] bin = new byte[4096 + 37];
        new Random(2026).nextBytes(bin);
        bin[0] = 0x00; bin[1] = 0x0A; bin[2] = 0x0D; bin[3] = (byte) 0xFF;
        bin[4] = 'E'; bin[5] = 'F'; bin[6] = 'I'; bin[7] = ' ';

        PipedInputStream hostIn = new PipedInputStream(1024 * 1024);
        PipedOutputStream devOut = new PipedOutputStream(hostIn);
        PipedInputStream devIn = new PipedInputStream(1024 * 1024);
        PipedOutputStream hostOut = new PipedOutputStream(devIn);

        MockAdbd dev = new MockAdbd(devIn, devOut, bin);
        dev.start();

        AdbBase64 b64 = new AdbBase64() {
            @Override public String encodeToString(byte[] data) {
                return Base64.getEncoder().encodeToString(data);
            }
        };
        AdbConnection conn = AdbConnection.create(hostIn, hostOut,
                AdbCrypto.generateAdbKeyPair(b64));
        conn.connect(15000);

        check("CNXN 握手完成", conn.isConnected(), "not connected");
        check("AUTH SIGNATURE 已发送且为 256B", dev.signatureSeen, "missing");
        check("AUTH RSA_PUBLIC 结构合法(524B)", dev.pubkeyValid, "invalid");
        check("设备已回 CNXN", dev.connected, "no device CNXN");
        check("协商 maxdata = 65536", conn.getMaxData() == 65536,
                String.valueOf(conn.getMaxData()));

        AdbClient adb = new AdbClient(new NoUi(), conn);

        // shell: 原始文本
        String text = adb.shell("echo hello", 8000);
        check("shell: 文本输出正确", "hello\n".equals(text), "[" + text + "]");

        // shellStream: 二进制零损失（分区 dd 的关键路径）
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        long n = adb.shellStream("su -c 'dd if=/dev/block/by-name/boot_a'", got, 8000);
        check("shellStream 字节数正确", n == bin.length, n + " vs " + bin.length);
        check("shellStream 二进制逐字节一致", Arrays.equals(bin, got.toByteArray()),
                "payload corrupted (PTY/CRLF?)");

        // sync: push
        byte[] file = new byte[200 * 1024 + 13];
        new Random(99).nextBytes(file);
        adb.push(new ByteArrayInputStream(file), file.length,
                "/data/local/tmp/test.bin", "0644", new Sink());
        check("sync: 收到 DONE", dev.syncDone, "no DONE frame");
        check("sync: push 内容逐字节一致", Arrays.equals(file, dev.pushed.toByteArray()),
                "pushed=" + dev.pushed.size() + " expected=" + file.length);
        check("OPEN 服务名解析正确", dev.openCount == 3 && dev.lastDest.equals("sync:"),
                "openCount=" + dev.openCount + " last=" + dev.lastDest);

        conn.close();
        dev.interrupt();
        System.out.println("\n回环结果: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
