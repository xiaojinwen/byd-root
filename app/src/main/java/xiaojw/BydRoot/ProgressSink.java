package xiaojw.BydRoot;

/** 大文件传输进度回调。 */
public interface ProgressSink {
    void onProgress(long done, long total);
    void onMessage(String msg);
}
