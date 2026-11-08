package com.cgutman.adblib;

/*
 * Based on AdbLib (https://github.com/cgutman/AdbLib)
 * Copyright (c) 2013, Cameron Gutman. BSD 2-Clause — see LICENSE in this package.
 *
 * Vendored and modified for BydRoot:
 *  - read() no longer discards queued payloads when the peer sends CLSE (original bug)
 *  - read(long)/write waits have timeouts so a stalled head unit cannot hang the UI thread
 *  - wasAccepted() records that an OKAY was seen, so a fast command that sends OKAY+WRTE+CLSE
 *    before open() wakes up is not misreported as "device refused the stream"
 */

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdbStream implements Closeable {

    private AdbConnection adbConn;
    private int localId;
    private int remoteId;
    private AtomicBoolean writeReady;
    private Queue<byte[]> readQueue;
    private volatile boolean isClosed;
    /** 是否收到过设备的 OKAY（一旦置 true 不再清零，即使随后立刻 CLSE）。 */
    private volatile boolean accepted;

    public AdbStream(AdbConnection adbConn, int localId) {
        this.adbConn = adbConn;
        this.localId = localId;
        this.readQueue = new ConcurrentLinkedQueue<byte[]>();
        this.writeReady = new AtomicBoolean(false);
        this.isClosed = false;
    }

    void addPayload(byte[] payload) {
        synchronized (readQueue) {
            readQueue.add(payload);
            readQueue.notifyAll();
        }
    }

    void sendReady() throws IOException {
        byte[] packet = AdbProtocol.generateReady(localId, remoteId);
        adbConn.outputStream.write(packet);
        adbConn.outputStream.flush();
    }

    void updateRemoteId(int remoteId) { this.remoteId = remoteId; }
    void readyForWrite() { accepted = true; writeReady.set(true); }
    boolean isWriteReady() { return writeReady.get(); }
    boolean wasAccepted() { return accepted; }

    void notifyClose() {
        isClosed = true;
        synchronized (this) { notifyAll(); }
        synchronized (readQueue) { readQueue.notifyAll(); }
    }

    /** Reads one WRTE payload. timeoutMs <= 0 waits indefinitely. */
    public byte[] read() throws InterruptedException, IOException { return read(0); }

    public byte[] read(long timeoutMs) throws InterruptedException, IOException {
        byte[] data;
        synchronized (readQueue) {
            long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;
            while ((data = readQueue.poll()) == null) {
                if (isClosed) throw new IOException("Stream closed");
                if (deadline != 0) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) throw new IOException("读取 ADB 流超时");
                    readQueue.wait(remain);
                } else {
                    readQueue.wait();
                }
            }
        }
        return data;
    }

    public void write(String payload) throws IOException, InterruptedException {
        write(payload.getBytes("UTF-8"), false);
        write(new byte[]{0}, true);
    }

    public void write(byte[] payload) throws IOException, InterruptedException {
        write(payload, true);
    }

    public void write(byte[] payload, boolean flush) throws IOException, InterruptedException {
        synchronized (this) {
            long deadline = System.currentTimeMillis() + 60000;
            while (!isClosed && !writeReady.compareAndSet(true, false)) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) throw new IOException("等待设备 OKAY 超时");
                wait(remain);
            }
            if (isClosed) throw new IOException("Stream closed");
        }
        byte[] packet = AdbProtocol.generateWrite(localId, remoteId, payload);
        adbConn.outputStream.write(packet);
        if (flush) adbConn.outputStream.flush();
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (isClosed) return;
            notifyClose();
        }
        byte[] packet = AdbProtocol.generateClose(localId, remoteId);
        adbConn.outputStream.write(packet);
        adbConn.outputStream.flush();
    }

    public boolean isClosed() { return isClosed; }
}
