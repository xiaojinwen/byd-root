package com.cgutman.adblib;

/*
 * Based on AdbLib (https://github.com/cgutman/AdbLib)
 * Copyright (c) 2013, Cameron Gutman. BSD 2-Clause — see LICENSE in this package.
 *
 * Vendored and modified for BydRoot:
 *  - create(InputStream, OutputStream, ...) lets the ADB protocol run over Android USB Host
 *  - connect()/open() timeouts, because the car head unit shows an authorization dialog
 *  - authentication callback so the UI can prompt "请在车机上勾选一律允许"
 *  - close() also closes bare streams (USB transport has no Socket)
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.Socket;
import java.util.HashMap;

public class AdbConnection implements Closeable {

    private Socket socket;
    private int lastLocalId;
    private InputStream inputStream;
    OutputStream outputStream;
    private Thread connectionThread;
    private boolean connectAttempted;
    private volatile boolean connected;
    private int maxData;
    private AdbCrypto crypto;
    private boolean sentSignature;
    private HashMap<Integer, AdbStream> openStreams;
    private volatile String lastError;
    private ConnectionListener listener;

    /** UI hooks for the RSA authorization handshake. */
    public interface ConnectionListener {
        void onAuthRequested();
        void onConnected();
    }

    private AdbConnection() {
        openStreams = new HashMap<Integer, AdbStream>();
        lastLocalId = 0;
        connectionThread = createConnectionThread();
    }

    /** TCP transport (WiFi ADB). */
    public static AdbConnection create(Socket socket, AdbCrypto crypto) throws IOException {
        AdbConnection c = new AdbConnection();
        c.crypto = crypto;
        c.socket = socket;
        c.inputStream = socket.getInputStream();
        c.outputStream = new SyncOutputStream(socket.getOutputStream());
        try { socket.setTcpNoDelay(true); } catch (Exception ignored) { }
        return c;
    }

    /** Generic transport — used by the USB Host packet-oriented streams. */
    public static AdbConnection create(InputStream in, OutputStream out, AdbCrypto crypto) {
        AdbConnection c = new AdbConnection();
        c.crypto = crypto;
        c.inputStream = in;
        c.outputStream = new SyncOutputStream(out);
        return c;
    }

    /**
     * ADB 的业务写（WRTE/sync）与连接进程的 OKAY/AUTH 写会并发发生，
     * 必须串行化，否则两条消息会在 USB 包边界上交错损坏。
     */
    private static final class SyncOutputStream extends OutputStream {
        private final OutputStream delegate;
        SyncOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }
        @Override public synchronized void write(int b) throws IOException { delegate.write(b); }
        @Override public synchronized void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    public void setConnectionListener(ConnectionListener l) { this.listener = l; }
    public boolean isConnected() { return connected; }
    public String getLastError() { return lastError; }

    private Thread createConnectionThread() {
        final AdbConnection conn = this;
        return new Thread(new Runnable() {
            @Override
            public void run() {
                while (!connectionThread.isInterrupted()) {
                    try {
                        AdbProtocol.AdbMessage msg = AdbProtocol.AdbMessage.parseAdbMessage(inputStream);
                        if (!AdbProtocol.validateMessage(msg)) continue;

                        switch (msg.command) {
                            case AdbProtocol.CMD_OKAY:
                            case AdbProtocol.CMD_WRTE:
                            case AdbProtocol.CMD_CLSE: {
                                if (!conn.connected) continue;
                                AdbStream waitingStream = openStreams.get(msg.arg1);
                                if (waitingStream == null) continue;
                                synchronized (waitingStream) {
                                    if (msg.command == AdbProtocol.CMD_OKAY) {
                                        waitingStream.updateRemoteId(msg.arg0);
                                        waitingStream.readyForWrite();
                                        waitingStream.notifyAll();
                                    } else if (msg.command == AdbProtocol.CMD_WRTE) {
                                        waitingStream.addPayload(msg.payload);
                                        waitingStream.sendReady();
                                    } else {
                                        conn.openStreams.remove(msg.arg1);
                                        waitingStream.notifyClose();
                                    }
                                }
                                break;
                            }
                            case AdbProtocol.CMD_AUTH: {
                                if (msg.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
                                    byte[] packet;
                                    if (conn.sentSignature) {
                                        packet = AdbProtocol.generateAuth(
                                                AdbProtocol.AUTH_TYPE_RSA_PUBLIC,
                                                conn.crypto.getAdbPublicKeyPayload());
                                        if (conn.listener != null) conn.listener.onAuthRequested();
                                    } else {
                                        packet = AdbProtocol.generateAuth(
                                                AdbProtocol.AUTH_TYPE_SIGNATURE,
                                                conn.crypto.signAdbTokenPayload(msg.payload));
                                        conn.sentSignature = true;
                                    }
                                    conn.outputStream.write(packet);
                                    conn.outputStream.flush();
                                }
                                break;
                            }
                            case AdbProtocol.CMD_CNXN: {
                                synchronized (conn) {
                                    conn.maxData = msg.arg1;
                                    conn.connected = true;
                                    conn.notifyAll();
                                }
                                if (conn.listener != null) conn.listener.onConnected();
                                break;
                            }
                            default:
                                break;
                        }
                    } catch (Exception e) {
                        conn.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                        break;
                    }
                }
                synchronized (conn) {
                    cleanupStreams();
                    conn.connected = false;
                    conn.connectAttempted = false;
                    conn.notifyAll();
                }
            }
        });
    }

    public int getMaxData() throws InterruptedException, IOException {
        if (!connectAttempted) throw new IllegalStateException("connect() must be called first");
        synchronized (this) {
            if (!connected) wait(1000);
            if (!connected) throw new IOException("Connection failed");
        }
        return maxData;
    }

    public void connect() throws IOException, InterruptedException { connect(90000); }

    public void connect(long timeoutMs) throws IOException, InterruptedException {
        if (connected) throw new IllegalStateException("Already connected");
        outputStream.write(AdbProtocol.generateConnect());
        outputStream.flush();
        connectAttempted = true;
        connectionThread.start();
        synchronized (this) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!connected) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new IOException("ADB 连接超时：车机未授权或 USB 连接已断开");
                wait(remain);
            }
        }
    }

    public AdbStream open(String destination)
            throws UnsupportedEncodingException, IOException, InterruptedException {
        return open(destination, 20000);
    }

    public AdbStream open(String destination, long timeoutMs)
            throws UnsupportedEncodingException, IOException, InterruptedException {
        int localId = ++lastLocalId;
        if (!connectAttempted) throw new IllegalStateException("connect() must be called first");
        synchronized (this) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!connected) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new IOException("ADB 未连接");
                wait(remain);
            }
        }
        AdbStream stream = new AdbStream(this, localId);
        openStreams.put(localId, stream);
        outputStream.write(AdbProtocol.generateOpen(localId, destination));
        outputStream.flush();
        synchronized (stream) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!stream.isClosed() && !stream.isWriteReady()) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new IOException("打开 ADB 流超时: " + destination);
                stream.wait(remain);
            }
        }
        // 只有「从未收到过 OKAY」才算被拒绝。执行极快的命令（如 echo）可能在
        // open() 被唤醒前就把 OKAY / WRTE / CLSE 一起送达，此时必须返回 stream，
        // 让调用方读走已排队的数据，否则会误报「设备拒绝打开流」。
        if (stream.isClosed() && !stream.wasAccepted()) {
            throw new ConnectException("设备拒绝打开流: " + destination);
        }
        return stream;
    }

    private void cleanupStreams() {
        for (AdbStream s : openStreams.values()) {
            try { s.close(); } catch (IOException ignored) { }
        }
        openStreams.clear();
    }

    @Override
    public void close() throws IOException {
        if (connectionThread != null) {
            connectionThread.interrupt();
            try { connectionThread.join(600); } catch (InterruptedException ignored) { }
        }
        try { if (inputStream != null) inputStream.close(); } catch (Exception ignored) { }
        try { if (outputStream != null) outputStream.close(); } catch (Exception ignored) { }
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        connected = false;
        synchronized (this) {
            cleanupStreams();
            notifyAll();
        }
    }
}
