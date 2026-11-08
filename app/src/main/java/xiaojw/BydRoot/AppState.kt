package xiaojw.BydRoot

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.concurrent.CountDownLatch

/** 一行带级别配色的运行日志。 */
data class LogLine(val text: String, val color: Color)

/** 待安装 APK（SAF Uri + 显示名 + 字节数）。 */
data class ApkItem(val uri: Uri, val name: String, val size: Long)

/**
 * worker 线程发起的阻塞式确认请求。UI 只负责展示与回填结果，
 * 真正的等待仍由 worker 上的 CountDownLatch 完成。
 */
class ConfirmRequest(
    val title: String,
    val message: String,
    val latch: CountDownLatch = CountDownLatch(1)
) {
    @Volatile var result: Boolean = false

    fun settle(ok: Boolean) {
        result = ok
        latch.countDown()
    }
}

/**
 * UI 层的二次确认请求：点操作按钮时先弹窗，只有用户点确认才执行 onConfirm。
 * 与 worker 阻塞式 ConfirmRequest 分开存放，避免流程内部确认覆盖入口确认。
 */
data class ActionConfirm(
    val title: String,
    val message: String,
    val confirmText: String = "确认执行",
    val danger: Boolean = false,
    val onConfirm: () -> Unit
)

/** 所有页面的统一入口；危险操作会显示红色确认按钮。 */
fun AppState.askConfirm(
    title: String,
    message: String,
    confirmText: String = "确认执行",
    danger: Boolean = false,
    action: () -> Unit
) {
    pendingAction = ActionConfirm(title, message, confirmText, danger, action)
}

/**
 * 全局 UI 状态（Compose Snapshot State）。
 * 所有写入统一经 Activity 的 main Handler 切回主线程，避免跨线程快照冲突。
 */
class AppState {
    var tab by mutableIntStateOf(0)

    var status by mutableStateOf("未连接")
    var info by mutableStateOf("资源目录：未选择")
    var dirDesc by mutableStateOf("未选择资源目录")
    /** 资源目录已选择且可读；一键流程与需要文件的单步都以此为准。 */
    var dirReady by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var progressText by mutableStateOf("待执行")
    var busy by mutableStateOf(false)
    var logExpanded by mutableStateOf(false)

    val logs = mutableStateListOf<LogLine>()

    // 「刷机 / 备份」页配置
    var wifiAdb by mutableStateOf(false)
    var host by mutableStateOf("")
    var port by mutableStateOf("5555")
    var includeBig by mutableStateOf(false)
    var verifySha by mutableStateOf(true)
    var autoBackup by mutableStateOf(true)

    // 「9008 强刷模式」页状态
    var edlDirDesc by mutableStateOf("未选择 9008 刷机包")
    var edlReady by mutableStateOf(false)
    var edlProgName by mutableStateOf("")
    var edlFirehoseName by mutableStateOf("")
    var edlPartCount by mutableIntStateOf(0)

    // 「工具 / 命令」页
    val apks = mutableStateListOf<ApkItem>()
    var rootInstall by mutableStateOf(false)
    var command by mutableStateOf("id")
    var runAsRoot by mutableStateOf(true)
    var timeoutSec by mutableStateOf("30")
    var cmdOutput by mutableStateOf("")

    // worker 线程发起的阻塞式确认弹窗
    var confirm by mutableStateOf<ConfirmRequest?>(null)
    // 点击操作按钮后待用户二次确认的动作
    var pendingAction by mutableStateOf<ActionConfirm?>(null)
}
