package xiaojw.BydRoot

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 主界面（Kotlin + Compose + miuix，与 KernelSU Manager 同一套 UI 组件库）：
 * 底栏两个 Tab —— 「刷机 / 备份」与「工具 / 命令」，共用全局进度条与日志。
 * 底层 ADB / fastboot / 全分区备份协议仍是已通过协议自测的 Java 实现。
 */
class MainActivity : ComponentActivity(), UiBridge, FlashActions, ToolsActions {

    companion object {
        private const val REQ_TREE = 42
        private const val REQ_RESTORE = 43
        private const val REQ_APK = 45
        private const val REQ_NOTIFICATION = 44
        private const val REQ_EDL_PACKAGE = 46
        private const val PREF = "BydRoot"
        private const val KEY_TREE = "tree"
        private const val KEY_WIFI = "wifi"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_BIG = "includeBig"
        private const val KEY_VERIFY = "verifySha"
        private const val KEY_AUTO_BACKUP = "autoBackup"
        private const val MAX_LOG_LINES = 500
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val state = AppState()
    private val settings = Workflow.Settings()

    /** 大文件传输（Firehose 镜像下发）进度回调，复用全局进度条。 */
    private val progressCb = object : ProgressSink {
        override fun onProgress(done: Long, total: Long) {
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            setProgress(pct, (done / 1024 / 1024).toString() + "MB / " + (total / 1024 / 1024).toString() + "MB")
        }
        override fun onMessage(msg: String) { log("info", msg) }
    }

    private var treeUri: Uri? = null
    private var restoreTreeUri: Uri? = null
    private var resDir: ResourceDir? = null
    private var workflow: Workflow? = null

    /** 9008 强刷用：独立选择的刷机包目录（含 rawprogram xml + 镜像 + Firehose 编程器）。 */
    private var edlTreeUri: Uri? = null
    private var edlDir: ResourceDir? = null

    @Volatile private var busy = false
    @Volatile private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()
        refreshInfo()
        setContent { AppTheme { RootUi() } }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
        }
        // 运行日志只记录真实操作输出，启动时不写入任何引导 / 版本信息。
    }

    // ---------------- Compose 界面 ----------------

    @Suppress("DEPRECATION")
    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val dark = isSystemInDarkTheme()
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        // miuix 的 Scaffold / TopAppBar / NavigationBar 都以 surface 铺底，
        // 系统栏取同一个色值，避免出现与界面不同的色带。
        val barColor = colors.surface.toArgb()
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            // 浅色导航栏默认会被系统额外盖一层对比遮罩，关掉才能与界面完全同色。
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        MiuixTheme(colors = colors) {
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                content()
            }
        }
    }

    @Composable
    private fun RootUi() {
        // 与「记忆小窗」同款折叠顶栏：内容列表上滑时大标题收起为顶栏小标题，下拉回弹展开。
        val scrollBehavior = MiuixScrollBehavior()
        // 切换 Tab 后回到展开态，避免沿用上个页面的收起位置。
        LaunchedEffect(state.tab) {
            scrollBehavior.state.heightOffset = 0f
            scrollBehavior.state.contentOffset = 0f
        }
        val listScroll = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = "车机 Root 工具",
                        largeTitle = "车机 Root 工具",
                        scrollBehavior = scrollBehavior,
                        actions = {
                            IconButton(onClick = { state.logExpanded = true }) {
                                Icon(
                                    imageVector = MiuixIcons.ListView,
                                    contentDescription = "运行日志",
                                    tint = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = state.tab == 0,
                            onClick = { state.tab = 0 },
                            icon = MiuixIcons.Backup,
                            label = "刷机 / 备份"
                        )
                        NavigationBarItem(
                            selected = state.tab == 1,
                            onClick = { state.tab = 1 },
                            icon = MiuixIcons.Settings,
                            label = "工具 / 命令"
                        )
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Box(Modifier.weight(1f)) {
                        when (state.tab) {
                            0 -> FlashScreen(state, this@MainActivity, listScroll)
                            else -> ToolsScreen(state, this@MainActivity, listScroll)
                        }
                    }
                    LogBar(state, onOpen = { state.logExpanded = true })
                }
            }
            // 日志抽屉覆盖在 Scaffold 之上，两个 Tab 共用；默认收起，不挤压内容区。
            if (state.logExpanded) {
                LogSheet(
                    state = state,
                    onCopy = { copyLog() },
                    onClear = {
                        state.askConfirm(
                            title = "清空运行日志？",
                            message = "将删除当前全部运行日志且无法恢复；正在执行的任务日志也会一并清掉。",
                            confirmText = "清空",
                            danger = true
                        ) { state.logs.clear() }
                    },
                    onClose = { state.logExpanded = false }
                )
            }
            ConfirmHost(state)
            ActionConfirmHost(state)

            // 返回键回退栈：从最上层开始逐层关闭，全部关完才把返回键交还系统（退出 app）。
            // 单一 BackHandler 自己判优先级，避免多个 BackHandler 的注册顺序产生歧义。
            BackHandler(
                enabled = state.pendingAction != null || state.confirm != null || state.logExpanded
            ) {
                when {
                    state.pendingAction != null -> state.pendingAction = null
                    state.confirm != null -> state.confirm?.settle(false)
                    state.logExpanded -> state.logExpanded = false
                }
            }
        }
    }

    // ---------------- FlashActions ----------------

    override fun pickResourceDir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_TREE)
    }

    override fun savePrefs() {
        val p = getSharedPreferences(PREF, MODE_PRIVATE)
        p.edit().apply {
            treeUri?.let { putString(KEY_TREE, it.toString()) }
            putBoolean(KEY_WIFI, state.wifiAdb)
            putString(KEY_HOST, state.host)
            putInt(KEY_PORT, state.port.trim().toIntOrNull() ?: 5555)
            putBoolean(KEY_BIG, state.includeBig)
            putBoolean(KEY_VERIFY, state.verifySha)
            putBoolean(KEY_AUTO_BACKUP, state.autoBackup)
        }.apply()
    }

    /** 一键流程与「需要读资源文件的单步」都必须先有可用目录，否则直接提示，不启动任务。 */
    private fun ensureResourceDir(): Boolean {
        if (treeUri == null || resDir == null || !state.dirReady) {
            toast("请先完成第 1 步：选择资源目录")
            return false
        }
        return true
    }

    override fun startFullFlow() {
        if (!ensureResourceDir()) return
        startTask {
            // 备份已作为完整流程的一步，在临时 Root 之后、刷入 boot 之前执行
            workflow().run(Workflow.FULL_FLOW)
        }
    }

    override fun stopTask() {
        cancelled = true
        log("warn", "已请求停止，将在当前步骤的检查点中断")
    }

    override fun startStep(step: Int) {
        if (Workflow.needsResourceDir(step) && !ensureResourceDir()) return
        startTask { workflow().run(intArrayOf(step)) }
    }

    /** 单独安装救砖模块；系统内 ADB + root，不需要 fastboot。 */
    override fun installGuardian() {
        if (!ensureResourceDir()) return
        startTask {
            workflow().run(intArrayOf(Workflow.FLASH_GUARDIAN))
        }
    }

    /** 完整流程的自动备份步骤：复用工作流当前的 ADB 连接，避免 USB Host 上开第二条连接。 */
    override fun onAutoBackup(): Boolean {
        if (!state.autoBackup) {
            log("info", "未开启自动备份，跳过")
            return true
        }
        if (treeUri == null) {
            log("err", "未选择资源目录，备份镜像无处写入")
            return false
        }
        log("info", "临时 Root 已就绪、boot 分区未改动，开始全分区备份…")
        return doBackup(false, state.includeBig, state.verifySha, workflow?.currentAdb())
    }

    override fun backup(viaFastboot: Boolean) {
        if (!ensureResourceDir()) return
        val includeBig = state.includeBig
        val verifySha = state.verifySha
        startTask { doBackup(viaFastboot, includeBig, verifySha) }
    }

    override fun restore() {
        if (busy) { toast("已有任务在执行"); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_RESTORE)
    }

    /** 9008 强刷：选择刷机包目录（rawprogram xml + 镜像 + Firehose 编程器）。 */
    override fun pickEdlPackage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EDL_PACKAGE)
    }

    /** 9008 强刷：校验刷机包后启动深刷任务。 */
    override fun startEdlFlash() {
        if (busy) { toast("已有任务在执行"); return }
        if (edlDir == null || !state.edlReady) {
            toast("请先选择包含 rawprogram 与 Firehose 的 9008 刷机包")
            return
        }
        startTask { doEdlFlash() }
    }

    // ---------------- ToolsActions ----------------

    override fun pickApks() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"   // 部分文件管理器把 .apk 报成 octet-stream，用 */* 最兼容
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_APK)
    }

    override fun addFromResourceDir() {
        val dir = resDir
        if (dir == null) {
            toast("请先在「刷机」页选择资源目录")
            return
        }
        val list = try {
            dir.list().filter { it.name.endsWith(".apk", ignoreCase = true) }
        } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) {
            toast("资源目录里没有 APK")
            return
        }
        var added = 0
        for (f in list) {
            if (state.apks.none { it.uri == f.uri }) {
                val size = if (f.size > 0) f.size else ToolOps.resolveSize(this, f.uri)
                state.apks.add(ApkItem(f.uri, f.name, size))
                added++
            }
        }
        toast("从资源目录添加了 $added 个 APK")
    }

    override fun clearApks() { state.apks.clear() }

    override fun installApks() {
        if (state.apks.isEmpty()) { toast("请先选择 APK"); return }
        val items = state.apks.toList()
        val asRoot = state.rootInstall
        startTask { ToolOps.installApks(this, this, { connectAdb() }, items, asRoot) }
    }

    override fun runCommand() {
        val raw = state.command.trim()
        if (raw.isEmpty()) { toast("请输入命令"); return }
        val asRoot = state.runAsRoot
        val timeoutSec = (state.timeoutSec.trim().toLongOrNull() ?: 30L).coerceIn(1L, 3600L)
        startTask {
            ToolOps.runCommand(this, { connectAdb() }, raw, asRoot, timeoutSec) { chunk ->
                appendOut(chunk)
            }
        }
    }

    override fun copyOutput() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("BydRoot cmd", state.cmdOutput))
        toast("输出已复制")
    }

    override fun clearOutput() { state.cmdOutput = "" }

    private fun appendOut(s: String) {
        main.post { state.cmdOutput += s }
    }

    // ---------------- SAF 结果 ----------------

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_APK -> onApkPicked(data)
            REQ_TREE -> {
                val tree = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(tree, ResourceDir.persistFlags())
                } catch (e: Exception) {
                    log("warn", "持久化目录权限失败：${e.message}")
                }
                treeUri = tree
                resDir = ResourceDir(this, tree)
                workflow?.setResourceDir(resDir)
                savePrefs()
                refreshInfo()
                log("ok", "资源目录已设置：${resDir!!.describe()}")
            }
            REQ_RESTORE -> {
                val tree = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (ignored: Exception) { }
                restoreTreeUri = tree
                startTask {
                    if (!awaitConfirm(
                            "危险操作确认",
                            "从备份恢复会逐分区覆盖车机内容，刷错镜像会导致彻底黑砖。\n\n" +
                                "请选择资源目录下 backup-… 开头的备份子目录（内含 manifest.json），" +
                                "不要选资源根目录，那里放的是刷机用的 boot-13.img 等文件。\n" +
                                "确认：备份来自同一台车机、同一系统版本，车机已进入 fastboot 并用 USB OTG 连接。\n" +
                                "点「继续」立即开始回刷；点「取消」放弃。"
                        )
                    ) {
                        log("warn", "用户取消恢复")
                        return@startTask
                    }
                    var fb: FastbootClient? = null
                    try {
                        val store = BackupStore(this, restoreTreeUri!!)
                        val pb = PartitionBackup(this, this, store)
                        fb = FastbootClient.connect(this, this, 120000)
                        val n = pb.restoreViaFastboot(fb, store.rootDocument())
                        log("ok", "已回刷 $n 个分区，请手动重启车机")
                        setStatus("恢复完成")
                    } catch (e: Exception) {
                        log("err", "恢复失败：${e.message}")
                        setStatus("恢复失败")
                    } finally {
                        fb?.close()
                    }
                }
            }
            REQ_EDL_PACKAGE -> {
                val tree = data.data ?: return
                try {
                    contentResolver.takePersistableUriPermission(
                        tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                } catch (ignored: Exception) { }
                edlTreeUri = tree
                edlDir = ResourceDir(this, tree)
            refreshEdlInfo()
        }
        }
    }

    private fun onApkPicked(data: Intent) {
        val uris = ArrayList<Uri>()
        data.clipData?.let { clip ->
            for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        } ?: data.data?.let { uris.add(it) }
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: Exception) { }
            val item = ToolOps.queryMeta(this, u)
            if (item == null) {
                log("warn", "无法读取所选文件：$u")
                continue
            }
            if (!item.name.endsWith(".apk", ignoreCase = true)) {
                log("warn", "所选文件 ${item.name} 扩展名不是 .apk，仍会尝试推送安装")
            }
            if (state.apks.none { it.uri == u }) state.apks.add(item)
        }
        log("info", "已选 ${state.apks.size} 个 APK，点「安装到车机」开始")
    }

    // ---------------- 任务调度 ----------------

    private fun workflow(): Workflow {
        val w = workflow ?: Workflow(this, this, resDir, settings).also { workflow = it }
        w.setResourceDir(resDir)
        return w
    }

    private fun syncSettings() {
        settings.wifiAdb = state.wifiAdb
        settings.host = state.host
        settings.port = state.port.trim().toIntOrNull() ?: 5555
    }

    private fun startTask(task: () -> Unit) {
        if (busy) { toast("已有任务在执行"); return }
        cancelled = false
        busy = true
        syncSettings()
        main.post {
            state.busy = true
            state.progress = 0
            state.progressText = "准备中"
        }
        val svc = Intent(this, TaskService::class.java)
        // 保活服务不是任务能否执行的前提；启动失败只提示，绝不让异常冒回点击回调导致闪退。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            log("warn", "前台保活服务启动失败：${e.message}")
        }
        worker.execute {
            try {
                task()
            } catch (t: Throwable) {
                // 捕获一切异常/错误（含 Error），绝不让其冒泡到线程顶层导致 app 闪退。
                try {
                    log("err", "任务异常：${t.message ?: t}")
                    setStatus("执行失败")
                } catch (ignored: Throwable) { }
            } finally {
                try { main.post { state.busy = false } } catch (ignored: Throwable) { }
                busy = false
                try { stopService(Intent(this@MainActivity, TaskService::class.java)) }
                catch (ignored: Throwable) { }
            }
        }
    }

    /** worker 线程执行；返回 false 表示备份失败，由调用方决定是否中止流程。 */
    private fun doBackup(
        viaFastboot: Boolean,
        includeBig: Boolean,
        verifySha: Boolean,
        sharedAdb: AdbClient? = null
    ): Boolean = try {
        val root = treeUri ?: throw java.io.IOException("请先选择资源目录，备份镜像需要写入该目录")
        val store = BackupStore(this, root)
        val pb = PartitionBackup(this, this, store)
        val opt = PartitionBackup.Options().apply {
            this.includeUserData = includeBig
            this.verifySha = verifySha
        }
        if (viaFastboot) {
            val fb = FastbootClient.connect(this, this, 120000)
            try { pb.backupViaFastboot(fb, opt) } finally { fb.close() }
        } else {
            val adb = sharedAdb ?: connectAdb()
            try { pb.backupViaAdb(adb, opt) } finally { if (sharedAdb == null) adb.close() }
        }
        setStatus("备份完成")
        setProgress(100, "备份完成")
        true
    } catch (e: Exception) {
        log("err", "备份失败：${e.message ?: e}")
        setStatus("备份失败")
        false
    }

    private fun connectAdb(): AdbClient {
        val adb = AdbClient(this, this)
        try {
            val h = settings.host.orEmpty()
            if (settings.wifiAdb && h.isNotBlank()) adb.connectTcp(h.trim(), settings.port, 15000)
            else adb.connectUsb(30000)
        } catch (e: Exception) {
            adb.close()
            throw e
        }
        return adb
    }

    // ---------------- 偏好与信息 ----------------

    private fun loadPrefs() {
        val p = getSharedPreferences(PREF, MODE_PRIVATE)
        p.getString(KEY_TREE, null)?.let {
            try {
                treeUri = Uri.parse(it)
                resDir = ResourceDir(this, treeUri!!)
            } catch (e: Exception) {
                log("warn", "恢复资源目录失败：${e.message}")
            }
        }
        state.wifiAdb = p.getBoolean(KEY_WIFI, false)
        state.host = p.getString(KEY_HOST, "") ?: ""
        state.port = p.getInt(KEY_PORT, 5555).toString()
        state.includeBig = p.getBoolean(KEY_BIG, false)
        state.verifySha = p.getBoolean(KEY_VERIFY, true)
        state.autoBackup = p.getBoolean(KEY_AUTO_BACKUP, true)
    }

    private fun refreshInfo() {
        val dir = resDir
        if (dir == null) {
            state.dirReady = false
            state.dirDesc = "未选择资源目录"
            state.info = "资源目录：未选择\n连接：USB OTG（车机 device / 手机 host）"
            return
        }
        state.dirDesc = dir.describe()
        val sb = StringBuilder("资源目录：${dir.describe()}\n")
        var readable = false
        try {
            val files = dir.list()
            readable = true   // 目录授权有效，才允许启动一键流程
            sb.append("检出文件 ${files.size} 个：\n")
            for (f in files) {
                val n = f.name.lowercase(Locale.US)
                if (n.startsWith("boot-") || n.endsWith(".zip") || n.endsWith(".apk") ||
                    n == "config.txt" || n.startsWith(PartitionBackup.PREFIX)
                ) sb.append("  · ${f.name}\n")
            }
        } catch (e: Exception) {
            sb.append("读取目录失败：${e.message}\n目录授权可能已失效，请重新选择目录")
        }
        state.dirReady = readable
        state.info = sb.toString().trim()
    }

    /** 扫描 9008 刷机包：定位 rawprogram、Firehose 编程器并统计可刷分区数，更新 UI 状态。 */
    private fun refreshEdlInfo() {
        val dir = edlDir
        if (dir == null) {
            state.edlReady = false
            state.edlDirDesc = "未选择 9008 刷机包"
            return
        }
        try {
            val raw = dir.findRawprogram()
            val firehose = dir.findFirehose()
            val progCount = if (raw != null) {
                try { EdlPackage.parse(dir.open(raw)).size } catch (_: Exception) { 0 }
            } else 0
            state.edlProgName = raw?.name ?: ""
            state.edlFirehoseName = firehose?.name ?: ""
            state.edlPartCount = progCount
            state.edlReady = raw != null && firehose != null && progCount > 0
            state.edlDirDesc = dir.describe() +
                if (raw == null) "（未找到 rawprogram*.xml）"
                else if (firehose == null) "（未找到 Firehose 编程器 .mbn/.elf）"
                else ""
            if (state.edlReady) {
                log("ok", "9008 刷机包就绪：${state.edlProgName} · ${state.edlFirehoseName} · ${progCount} 个分区")
            } else {
                log("warn", "9008 刷机包不完整：${state.edlDirDesc}")
            }
        } catch (e: Exception) {
            state.edlReady = false
            state.edlDirDesc = "读取目录失败：${e.message}；目录授权可能已失效，请重新选择"
        }
    }

    /**
     * 9008 强刷编排（后台线程执行）：
     * 连接 EDL → Sahara 握手 → 上传 Firehose → configure → 按 rawprogram 逐分区 program → reset。
     * 刷写过程不可取消（避免写到一半断线变砖）；仅在各分区之间检查取消。
     */
    private fun doEdlFlash() {
        val dir = edlDir
        if (dir == null) { log("err", "未选择 9008 刷机包"); setStatus("未选择刷机包"); return }
        val raw = dir.findRawprogram()
            ?: run { log("err", "刷机包里没有 rawprogram*.xml"); setStatus("缺少 rawprogram"); return }
        val firehose = dir.findFirehose()
            ?: run { log("err", "未找到 Firehose 编程器（.mbn/.elf）"); setStatus("缺少 Firehose"); return }
        val progs = try {
            EdlPackage.parse(dir.open(raw))
        } catch (e: Exception) {
            log("err", "rawprogram 解析失败：${e.message}"); setStatus("解析失败"); return
        }
        if (progs.isEmpty()) { log("err", "rawprogram 中没有可刷分区"); setStatus("无分区"); return }

        // 解析每个分区对应的镜像文件（包内可能平铺或分目录存放）
        val tasks = ArrayList<Pair<EdlPackage.Program, ResourceDir.FileRef>>()
        for (p in progs) {
            val name = p.filename
            if (name.isNullOrEmpty()) { log("warn", "跳过 ${p.label}：rawprogram 未指定镜像文件名"); continue }
            val img = dir.findRecursive(name)
            if (img == null) { log("warn", "跳过 ${p.label}：找不到镜像 ${name}"); continue }
            tasks.add(Pair(p, img))
        }
        if (tasks.isEmpty()) { log("err", "没有任何可刷镜像文件（检查 rawprogram 中的 filename 与包内文件）"); setStatus("无镜像"); return }

        log("info", "待刷分区 ${tasks.size} 个，开始连接 9008 设备…")
        var client: EdlClient? = null
        try {
            client = EdlClient.connect(this, this, 60000)
            val hs = client.handshake()
            if (hs.mode == EdlClient.MODE_IMAGE_TX_PENDING) {
                val fhSize = dir.sizeOf(firehose)
                log("info", "上传 Firehose：${firehose.name}（${fhSize / 1024}KB）")
                client.uploadFirehose(dir.open(firehose), fhSize, progressCb)
                client.endTransfer(true)
                client.enterFirehoseAndConfigure()
            } else {
                log("info", "设备已在 Command Mode，跳过 Firehose 上传")
                client.enterFirehoseAndConfigure()
            }
            for ((p, img) in tasks) {
                if (isCancelled()) { log("warn", "用户取消，停在分区 ${p.label} 之前"); break }
                val size = dir.sizeOf(img)
                if (size <= 0) { log("warn", "跳过 ${p.label}：镜像大小为 0"); continue }
                log("step", "刷写 ${p.label}（${size / 1024 / 1024}MB）")
                client.program(p, dir.open(img), size, progressCb)
                log("ok", "已刷写 ${p.label}")
            }
            client.resetDevice()
            log("ok", "9008 强刷完成，车机将重启。若未自动开机请手动上电。")
            setStatus("9008 强刷完成")
        } catch (e: Exception) {
            log("err", "9008 强刷失败：${e.message}")
            setStatus("强刷失败")
        } finally {
            client?.close()
        }
    }

    private fun copyLog() {
        val text = state.logs.joinToString("\n") { it.text }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("BydRoot log", text))
        toast("日志已复制")
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------- UiBridge ----------------

    override fun log(level: String, msg: String) {
        main.post {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val pair = when (level) {
                "ok" -> "[成功]" to Color(0xFF5BD98B)
                "warn" -> "[注意]" to Color(0xFFF5C260)
                "err" -> "[错误]" to Color(0xFFFF6B6B)
                "step" -> "[步骤]" to Color(0xFF6FA8FF)
                else -> "[信息]" to Color(0xFF9AA8B8)
            }
            state.logs.add(LogLine("[$ts] ${pair.first} $msg", pair.second))
            if (state.logs.size > MAX_LOG_LINES) state.logs.removeAt(0)
        }
    }

    override fun setStatus(text: String) {
        main.post { state.status = text }
    }

    override fun setProgress(percent: Int, text: String) {
        main.post {
            state.progress = percent.coerceIn(0, 100)
            state.progressText = text
        }
    }

    override fun setBusy(isBusy: Boolean) {
        // Java 层 Workflow.run() 的 finally 会回调 false。任务生命周期现在由
        // startTask 统一收口，这里只在没有任务运行时同步，避免流程中途提前解锁按钮。
        main.post { if (!busy) state.busy = isBusy }
    }

    override fun awaitConfirm(title: String, message: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "awaitConfirm 不能在主线程调用" }
        val req = ConfirmRequest(title, message)
        main.post { state.confirm = req }
        return try {
            req.latch.await(10, TimeUnit.MINUTES)
            val ok = req.result
            main.post { state.confirm = null }
            ok
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            main.post { state.confirm = null }
            false
        }
    }

    override fun isCancelled(): Boolean = cancelled

    override fun onDestroy() {
        cancelled = true
        savePrefs()
        workflow?.close()
        stopService(Intent(this, TaskService::class.java))
        worker.shutdownNow()
        super.onDestroy()
    }
}
