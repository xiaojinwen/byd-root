package xiaojw.BydRoot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「刷机 / 备份」页需要的动作，全部由 MainActivity 落到已验证的 Java 协议层。 */
interface FlashActions {
    fun pickResourceDir()
    fun savePrefs()
    fun startFullFlow()
    fun stopTask()
    fun startStep(step: Int)
    fun installGuardian()
    fun backup(viaFastboot: Boolean)
    fun restore()
    fun pickEdlPackage()
    fun startEdlFlash()
}

/**
 * 按真实操作顺序排布：状态 → ① 准备资源目录（含教程）→ ② 连接车机 → ③ 一键执行
 * → ④ 备份与恢复 → ⑤ 单独装救砖模块 → 分步排查（默认收起）。
 * 未选资源目录时一键按钮不可用，从 UI 层杜绝空目录启动任务。
 */
@Composable
fun FlashScreen(
    state: AppState,
    actions: FlashActions,
    scrollModifier: Modifier = Modifier
) {
    var hostField by remember { mutableStateOf(TextFieldValue(state.host)) }
    var portField by remember { mutableStateOf(TextFieldValue(state.port)) }
    var showGuide by remember { mutableStateOf(true) }
    var showDirDetail by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showSteps by remember { mutableStateOf(false) }
    var showEdlHelp by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    val enabled = !state.busy
    val dirReady = state.dirReady
    val canRun = enabled && dirReady

    LazyColumn(
        modifier = Modifier.fillMaxSize().then(scrollModifier),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item { StatusHero(state) }

        item {
            TabRow(
                tabs = listOf("常规刷机", "9008 强刷"),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        if (selectedTab == 0) {
        // ---------- ① 准备资源目录 ----------
        item {
            StepTitle("1", "准备资源目录")
            CardBox {
                if (!dirReady) {
                    Text(
                        text = "未选择资源目录 · 一键流程无法读取 boot 镜像和模块包",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                SuperArrow(
                    title = if (dirReady) "重新选择资源目录" else "选择资源目录",
                    summary = state.dirDesc,
                    onClick = { if (enabled) actions.pickResourceDir() }
                )
                HintText(
                    "自行准备刷机文件：按下面教程的文件名格式，平铺放进手机上一个文件夹，再选中这个文件夹。" +
                        "app 不附带 boot 镜像和待刷模块包，需你自己准备；救砖守护模块也需自备放入资源目录（文件名含防砖/救砖）。" +
                        "该目录同时是备份镜像的写入位置，请留足空间并授权读写。",
                    Modifier.padding(top = 8.dp)
                )
                FoldRow(if (showGuide) "收起准备教程" else "目录里放什么？点开看教程与文件名格式", showGuide) {
                    showGuide = !showGuide
                }
                if (showGuide) GuideContent()
                FoldRow("目录详情 · 已检出的文件", showDirDetail) { showDirDetail = !showDirDetail }
                if (showDirDetail) {
                    Text(
                        text = state.info,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }

        // ---------- ② 连接车机 ----------
        item {
            StepTitle("2", "连接车机")
            CardBox {
                HintText(
                    "手机用 USB OTG 数据线连车机；车机需开启 USB 调试，首次连接在 ADB 授权弹窗点「允许」。" +
                        "fastboot 刷写阶段只能用 USB OTG，全程请勿拔线。"
                )
                SuperSwitch(
                    title = "WiFi / 网络 ADB",
                    summary = "仅系统内 ADB 阶段可用；fastboot 刷写必须走 USB OTG",
                    checked = state.wifiAdb,
                    onCheckedChange = { state.wifiAdb = it; actions.savePrefs() },
                    enabled = enabled
                )
                if (state.wifiAdb) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextField(
                            value = hostField,
                            onValueChange = { hostField = it; state.host = it.text; actions.savePrefs() },
                            modifier = Modifier.weight(1f),
                            label = "车机 IP，如 192.168.1.100",
                            useLabelAsPlaceholder = true,
                            enabled = enabled,
                            singleLine = true
                        )
                        TextField(
                            value = portField,
                            onValueChange = { portField = it; state.port = it.text; actions.savePrefs() },
                            modifier = Modifier.weight(0.45f),
                            label = "端口",
                            useLabelAsPlaceholder = true,
                            enabled = enabled,
                            singleLine = true
                        )
                    }
                }
                FoldRow("连接模式速查 · 哪些流程要进 fastboot", showModes) { showModes = !showModes }
                if (showModes) {
                    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
                        ModeGroupTitle("需要 fastboot / bootloader 模式")
                        ModeRow("临时 Root（单步 4）", "fastboot boot", true)
                        ModeRow("长期 Root（单步 5）", "fastboot flash boot", true)
                        ModeRow("fastboot 回读备份（只读）", "getvar + fetch", true)
                        ModeRow("从备份恢复", "fastboot flash", true)
                        ModeRow("一键完整流程（中途自动切换）", "含 Root 两步", true)

                        Spacer(Modifier.size(12.dp))
                        ModeGroupTitle("需要系统内 ADB（车机已开机）")
                        ModeRow("识别版本 / 校验文件 / 验证 Root", "ADB", false)
                        ModeRow("安装 Magisk / MT、禁用下载应用、修复小迪", "ADB", false)
                        ModeRow("安装救砖模块（装完自动重启）、刷模块包", "ADB + root", false)
                        ModeRow("全分区备份（临时 Root 后）、清缓存 / 重启", "ADB + root", false)
                        ModeRow("工具页：安装 APK / 任意命令", "ADB", false)

                        HintText(
                            "临时 / 长期 Root 由 app 自动执行 adb reboot bootloader，无需手动进 fastboot；" +
                                "只有「fastboot 回读备份」「从备份恢复」需要你先把车机停在 bootloader 画面。" +
                                "回读是把分区镜像读回手机备份，不会写入车机。",
                            Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
        }

        // ---------- ③ 一键执行完整流程 ----------
        item {
            StepTitle("3", "一键执行完整流程")
            CardBox {
                if (!dirReady) {
                    Text(
                        text = "请先完成第 1 步：选择资源目录",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        "一键执行完整流程",
                        onClick = {
                            state.askConfirm(
                                title = "执行完整流程？",
                                message = "将依次完成：识别版本 → 校验文件 → 临时 Root → 全分区备份 → 长期 Root → " +
                                    "装 Magisk / MT → 装救砖模块并重启自检（约 90 秒）→ 刷模块包 → 清缓存重启。\n\n" +
                                    "boot 分区会被改写，车机会多次重启；全程需保持 USB OTG 连接，请勿拔线或断电。",
                                confirmText = "开始执行",
                                danger = true
                            ) { actions.startFullFlow() }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = canRun
                    )
                    SecondaryButton(
                        "停止",
                        onClick = {
                            state.askConfirm(
                                title = "停止当前任务？",
                                message = "会在当前步骤的检查点中断流程。车机可能停留在 fastboot、或模块安装到一半，之后需要手动收尾。",
                                confirmText = "停止任务",
                                danger = true
                            ) { actions.stopTask() }
                        },
                        enabled = state.busy
                    )
                }
                HintText(
                    "连接 → 识别版本 → 校验文件 → 临时 Root → 全分区备份 → 长期 Root → " +
                        "装 Magisk / MT → 装救砖模块并重启自检 → 刷模块包 → 清缓存重启。" +
                        "救砖守护会先单独重启并等约 90 秒自检，再刷风险模块；中途 Root 两步自动切 fastboot。\n\n" +
                        "自动备份由第 4 步「备份选项」里的开关控制，默认开启，且会先弹窗让你确认预计体积。" +
                        "每次备份单独新建 backup-<时间戳> 子目录，不会覆盖目录里平铺的资源文件。",
                    Modifier.padding(top = 10.dp)
                )
            }
        }

        // ---------- ④ 备份与恢复 ----------
        item {
            StepTitle("4", "备份与恢复 · 防黑砖")
            CardBox {
                HintText(
                    "一键流程会在临时 Root 后自动备份（boot 尚未改动）。这里可单独用 ADB 备份、fastboot 回读备份、从备份恢复：" +
                        "导出全部 by-name 分区、主备 GPT、SHA-256 清单，以及 PC 回刷脚本和 9008 深刷用 rawprogram.xml。\n\n" +
                        "每次备份都在资源目录下新建独立的 backup-<时间戳> 子目录，不会覆盖或改名同目录里的 " +
                        "boot-*.img / 模块包 .zip / Magisk.apk 等资源文件；恢复时请选这个 backup- 子目录。"
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryButton(
                        "ADB 全分区备份",
                        onClick = {
                            state.askConfirm(
                                title = "开始 ADB 全分区备份？",
                                message = "要求车机已开机且已 root。将把 by-name 分区、主备 GPT 与 SHA-256 清单写进资源目录；" +
                                    "若勾选了大分区，可能占用数十 GB 空间并耗时较长。",
                                confirmText = "开始备份"
                            ) { actions.backup(false) }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = canRun
                    )
                    SecondaryButton(
                        "fastboot 回读备份",
                        onClick = {
                            state.askConfirm(
                                title = "开始 fastboot 回读备份？",
                                message = "需先把车机停在 bootloader 画面，且只能用 USB OTG。app 用 getvar 查分区大小、" +
                                    "fetch 把分区原始镜像读回手机，只读不写，不改动车机任何分区。\n\n" +
                                    "部分车机 bootloader 可能不支持 fetch；若不支持会明确报错，请改用 ADB 备份。",
                                confirmText = "开始回读"
                            ) { actions.backup(true) }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = canRun
                    )
                }
                DangerButton(
                    "从备份恢复",
                    onClick = {
                        state.askConfirm(
                            title = "从备份恢复分区？",
                            message = "会用备份目录里的镜像通过 fastboot flash 覆盖车机分区，操作不可逆、可能导致无法开机。\n\n" +
                                "下一步请选资源目录下 backup-… 开头的备份子目录（含 manifest.json），不要选资源根目录。\n" +
                                "请确认车机已停在 bootloader、镜像与机型版本一致，并全程保持 USB OTG 连接。",
                            confirmText = "确认恢复",
                            danger = true
                        ) { actions.restore() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = enabled
                )
                ModeHint(
                    "回读 = 在 bootloader 下把分区原始镜像读回手机存成备份；只读不写，不改动车机，系统起不来时也能用。\n" +
                        "ADB 备份：车机已开机并 root；回读 / 恢复：车机停在 bootloader，仅 USB OTG。"
                )
                FoldRow("备份选项", showAdvanced) { showAdvanced = !showAdvanced }
                if (showAdvanced) {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        SuperSwitch(
                            title = "包含 userdata / data 等大分区",
                            summary = "可能几十 GB，先确认手机剩余空间",
                            checked = state.includeBig,
                            onCheckedChange = { state.includeBig = it; actions.savePrefs() },
                            enabled = enabled
                        )
                        SuperSwitch(
                            title = "与车机端 sha256sum 双向校验",
                            summary = "更慢，但能拦下被 PTY / CRLF 改写的坏镜像",
                            checked = state.verifySha,
                            onCheckedChange = { state.verifySha = it; actions.savePrefs() },
                            enabled = enabled
                        )
                        SuperSwitch(
                            title = "临时 Root 后自动全分区备份",
                            summary = "推荐开启：此时 boot 尚未刷入，能保留原始镜像",
                            checked = state.autoBackup,
                            onCheckedChange = { state.autoBackup = it; actions.savePrefs() },
                            enabled = enabled
                        )
                    }
                }
            }
        }

        // ---------- ⑤ 单独安装救砖模块 ----------
        item {
            StepTitle("5", "单独安装救砖模块", optional = true)
            CardBox {
                HintText(
                    "救砖守护模块：当模块导致系统无法开机时，连续重启 3 次或卡开机 90 秒后自动禁用问题模块。" +
                        "完整流程已包含这一步；已 root 的车机也可单独安装，需先在资源目录放入含防砖/救砖 的救砖模块包。"
                )
                PrimaryButton(
                    "安装救砖模块",
                    onClick = {
                        state.askConfirm(
                            title = "安装救砖模块？",
                            message = "将把资源目录里的救砖守护模块推送并安装到车机，装完会自动重启使其生效。" +
"需要车机已 root 且已装好 Magisk，并已在资源目录放入含防砖/救砖 的救砖模块包。",
                            confirmText = "安装并重启"
                        ) { actions.installGuardian() }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    enabled = canRun
                )
                ModeHint("系统内 ADB + root · 不需要 fastboot · 不需要额外文件")
            }
        }

        // ---------- 分步执行（排查用，默认收起） ----------
        item {
            FoldableTitle(
                text = "分步执行 · 排查用",
                expanded = showSteps,
                hint = "${Workflow.STEP_ORDER.size} 步 · 与完整流程同序",
                onToggle = { showSteps = !showSteps }
            )
        }
        if (showSteps) {
            item {
                HintText(
                    "单步用于定位问题：每步会按需自动补齐前置检测；标红的是需要 fastboot 的步骤，带文件的步骤需先选好资源目录。",
                    Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            val steps = Workflow.STEP_ORDER
            items((steps.size + 1) / 2) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (col in 0 until 2) {
                        val pos = row * 2 + col
                        if (pos < steps.size) {
                            val id = steps[pos]
                            val fastboot = Workflow.isFastbootStep(id)
                            Button(
                                onClick = {
                                    val name = Workflow.STEP_NAMES[id]
                                    state.askConfirm(
                                        title = "执行第 ${pos + 1} 步：$name？",
                                        message = (if (fastboot)
                                            "这一步在 fastboot / bootloader 模式下执行；只能用 USB OTG，请勿拔线。\n\n"
                                        else
                                            "这一步在系统内 ADB 下执行（车机需已开机）。\n\n") +
                                            "单步会按需自动补齐前置检测；需要资源文件的步骤请先完成第 1 步。",
                                        confirmText = "执行这一步",
                                        danger = fastboot
                                    ) { actions.startStep(id) }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                enabled = enabled,
                                minWidth = 0.dp,
                                minHeight = 0.dp,
                                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                // miuix Button 内部是 Arrangement.Center 的 Row，内容不撑满宽度
                                // 就会被整体居中；这里撑满并显式左对齐，同时保留左右内边距。
                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "${pos + 1}. ${Workflow.STEP_NAMES[id]}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (enabled) MiuixTheme.colorScheme.onSecondaryVariant
                                        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                    )
                                    Text(
                                        text = if (fastboot) "fastboot" else "系统内 ADB",
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                        color = when {
                                            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                            fastboot -> MiuixTheme.colorScheme.error
                                            else -> MiuixTheme.colorScheme.onBackgroundVariant
                                        }
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "BydRoot · 手机 USB OTG 代替 PC 完成车机 Root / 模块部署 / 全分区备份",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
        } // ← 常规刷机 tab 结束

        if (selectedTab == 1) {
            // ---------- 9008 强刷救砖（深刷 EDL） ----------
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "9008 强刷救砖（深刷 EDL）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                }
                CardBox {
                    HintText(
                        "9008 深刷绕过 bootloader 直接重写存储，用于系统彻底起不来（黑砖）的救砖。" +
                            "需要车机进入高通 9008 EDL 模式（USB 枚举为 05C6:9008），并提供对应机型的 Firehose 编程器与刷机包。" +
                            "刷机包目录需含 rawprogram*.xml（本工具备份会生成 rawprogram-restore.xml）+ 各分区 .img + 一个 Firehose 编程器（.mbn/.elf）。"
                    )
                    SuperArrow(
                        title = if (state.edlReady) "重新选择 9008 刷机包" else "选择 9008 刷机包目录",
                        summary = state.edlDirDesc,
                        onClick = { if (enabled) actions.pickEdlPackage() }
                    )
                    if (state.edlReady) {
                        HintText(
                            "已识别 rawprogram：${state.edlProgName}\nFirehose：${state.edlFirehoseName}\n" +
                                "待刷分区：${state.edlPartCount} 个",
                            Modifier.padding(top = 8.dp)
                        )
                    }
                    FoldRow(
                        "如何进入 9008 / 风险提示",
                        showEdlHelp
                    ) { showEdlHelp = !showEdlHelp }
                    if (showEdlHelp) {
                        HintText(
                            "进入 9008：车机已开机时可在「工具 / 命令」页执行 adb reboot edl；或关机后短接测试点 / 进深刷模式。" +
                                "连上手机后 USB 会枚举为 05C6:9008。\n\n" +
                                "警告：深刷会按 rawprogram 从头重写分区，不可逆，且必须与你车机型号 / 版本严格匹配，刷错会彻底变黑砖。" +
                                "请确认刷机包来自同一台车机的备份，且车机已停在 9008、全程 USB OTG 不断线。",
                            Modifier.padding(top = 2.dp, bottom = 4.dp)
                        )
                    }
                    DangerButton(
                        "开始 9008 强刷",
                        onClick = {
                            state.askConfirm(
                                title = "开始 9008 强刷？",
                                message = "将按 rawprogram（${state.edlPartCount} 个分区）通过 9008 EDL 直接重写车机存储，操作不可逆。\n\n" +
                                    "请确认：① 车机已处于 9008 EDL 模式；② 刷机包来自本车备份且型号一致；③ USB OTG 全程连接稳定。\n" +
                                    "点「确认强刷」立即开始；点「取消」放弃。",
                                confirmText = "确认强刷",
                                danger = true
                            ) { actions.startEdlFlash() }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = state.edlReady && enabled
                    )
                    ModeHint(
                        "需车机停在 9008 EDL（USB 05C6:9008）；绕过 fastboot，直接重写分区，用于黑砖救砖。" +
                            "刷机过程不可取消，请先确认刷机包与机型匹配。"
                    )
                }
            }
        }
    }
}

/** 带编号的步骤标题，明确操作顺序。 */
@Composable
private fun StepTitle(index: String, title: String, optional: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(index, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onBackground)
        if (optional) {
            Spacer(Modifier.width(8.dp))
            Text("可选", fontSize = 10.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
    }
}

/** 资源目录准备教程：文案与 ResourceDir / DeviceConfig 的真实匹配规则一致。 */
@Composable
private fun GuideContent() {
    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        GuideLine(
            "1", "建一个文件夹，文件全部平铺",
            "按下面 2～5 条的文件名格式自行准备文件，直接放进手机上一个文件夹。app 只读取这一层，" +
                "不会进子文件夹；文件名和扩展名不要改。boot 镜像、待刷模块包、救砖模块都需自备放入资源目录。"
        )
        GuideLine(
            "2", "必放 · boot 镜像",
            "boot-13.img / boot-15.img / boot-17.img / boot-18.img / boot-21.img，按车机版本放对应的一个即可（全放也行）。" +
                "车机 outswver 第一段决定编号，例如 13.1.22.2511250.1 → boot-13.img。文件名大小写不敏感，MD5 必须与 config.txt 一致。"
        )
        GuideLine(
"3", "必放 · 待刷模块包（.zip）",
"把要刷入的 Magisk 模块 zip 放进目录，建议只放这一个。优先选文件名以 4.5ui- 开头的（不区分大小写）；" +
"目录里多个 zip 时，含防砖 / 救砖 / guardian 的会被识别为救砖模块并跳过，其余按文件名排序取第一个；" +
"文件名按 <名称>_<版本>_<32位MD5>.zip 命名时，第三段 MD5 会在校验阶段自动核对；没有可用 .zip 会直接报缺少模块包。"
        )
        GuideLine(
            "4", "建议放 · 两个软件安装包",
            "Magisk.apk、MT.apk，文件名必须精确（大小写不敏感）。缺了会跳过安装，但后续要在车机 Magisk 里点「修复」，所以建议放。" +
                "其它 .apk 也可以放，工具页「从资源目录添加」会列出来。"
        )
        GuideLine(
            "5", "可选 · config.txt",
            "每行格式「控制器版本 核心版本 boot.img 的 MD5」，例：13 2511250 B3EDE7F5A948EFD8D3FE764E5418A304。" +
                "不放就用 app 内置版本表（13 / 15 / 17 / 18 / 21）。"
        )
        GuideLine(
"6", "必放 · 救砖模块（.zip）",
"文件名含防砖 / 救砖（或 guardian）才会被识别为救砖模块；这类 zip 会被跳过，不会当成待刷模块包。APK 不再内置，需自备放入。"
        )
        Spacer(Modifier.size(4.dp))
        HintText(
            "最后点上方「选择资源目录」，在系统文件管理器里选中这个文件夹本身并授权读写。备份镜像也会写回这里。"
        )
    }
}

@Composable
private fun GuideLine(no: String, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(no, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onBackground)
            Text(body, fontSize = 11.sp, lineHeight = 16.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 卡片内的轻量折叠行。 */
@Composable
private fun FoldRow(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.primary
        )
    }
}

/** 一行小字：说明这个操作在哪种连接模式下执行。 */
@Composable
private fun ModeHint(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ModeGroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** 速查行：左侧流程名，右侧模式徽标（fastboot 用危险色，ADB 用主色）。 */
@Composable
private fun ModeRow(name: String, mode: String, fastboot: Boolean) {
    val tint = if (fastboot) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
            modifier = Modifier.weight(1f)
        )
        Box(
            Modifier
                .background(tint.copy(alpha = 0.13f), RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(text = mode, fontSize = 10.sp, maxLines = 1, color = tint)
        }
    }
}
