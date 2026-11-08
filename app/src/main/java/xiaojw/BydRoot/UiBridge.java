package xiaojw.BydRoot;

/** UI 回调：工作流运行在后台线程，所有界面更新/阻塞式确认都经此桥接回主线程。 */
public interface UiBridge {
    void log(String level, String msg);
    void setStatus(String text);
    void setProgress(int percent, String text);
    void setBusy(boolean busy);
    /** 阻塞等待用户确认；返回 true 表示继续，false 表示取消。 */
    boolean awaitConfirm(String title, String message);
    boolean isCancelled();
    /**
     * 自动全分区备份钩子：临时 Root 成功后、长期 Root 刷入 boot 前由工作流调用。
     * 宿主可复用 Workflow.currentAdb() 的连接；返回 false 表示备份失败应中止流程。
     */
    boolean onAutoBackup();
}
