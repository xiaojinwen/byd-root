package xiaojw.BydRoot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

interface ToolsActions {
    fun pickApks()
    fun addFromResourceDir()
    fun clearApks()
    fun installApks()
    fun runCommand()
    fun copyOutput()
    fun clearOutput()
}

private val QUICK_COMMANDS = listOf(
    "id" to "id",
    "系统属性" to "getprop ro.build.version.release",
    "分区表" to "ls -l /dev/block/by-name/",
    "Magisk" to "magisk -v",
    "存储" to "df -h",
    "进 fastboot" to "reboot bootloader"
)

/**
 * 工具页按「选择 → 安装 / 输入 → 执行 → 输出」单向流排列，
 * APK 列表收回卡片内，快捷命令两行排布，输出区跟随系统深浅色。
 */
@Composable
fun ToolsScreen(
    state: AppState,
    actions: ToolsActions,
    scrollModifier: Modifier = Modifier
) {
    var cmdField by remember { mutableStateOf(TextFieldValue(state.command)) }
    var timeoutField by remember { mutableStateOf(TextFieldValue(state.timeoutSec)) }
    val outputScroll = rememberScrollState()
    LaunchedEffect(state.cmdOutput.length) { outputScroll.scrollTo(outputScroll.maxValue) }
    val enabled = !state.busy

    LazyColumn(
        modifier = Modifier.fillMaxSize().then(scrollModifier),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp)
    ) {
        // ---------- 安装软件 ----------
        item {
            SectionTitle("安装软件到车机")
            CardBox {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(
                        "选择 APK（可多选）",
                        onClick = { actions.pickApks() },
                        modifier = Modifier.weight(1f),
                        enabled = enabled
                    )
                    SecondaryButton(
                        "从资源目录添加",
                        onClick = { actions.addFromResourceDir() },
                        modifier = Modifier.weight(1f),
                        enabled = enabled
                    )
                }

                Spacer(Modifier.size(12.dp))
                if (state.apks.isEmpty()) {
                    Text(
                        text = "未选择 APK · 推送到车机后执行 pm install -r",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MiuixTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        state.apks.forEachIndexed { index, apk ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.width(20.dp)
                                )
                                Text(
                                    text = apk.name,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = ToolOps.fmtSize(apk.size),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                                )
                            }
                        }
                    }
                }

                SuperSwitch(
                    title = "以 root 安装",
                    summary = "su -c pm install，车机需已 root",
                    checked = state.rootInstall,
                    onCheckedChange = { state.rootInstall = it },
                    enabled = enabled
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SecondaryButton(
                        "清空列表",
                        onClick = {
                            state.askConfirm(
                                title = "清空 APK 列表？",
                                message = "将移除已选的 ${state.apks.size} 个 APK（不会删除手机上的文件）。",
                                confirmText = "清空"
                            ) { actions.clearApks() }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = enabled
                    )
                    PrimaryButton(
                        "安装到车机",
                        onClick = {
                            state.askConfirm(
                                title = "安装 ${state.apks.size} 个 APK 到车机？",
                                message = "将把已选 APK 推送到车机 /data/local/tmp 并执行 pm install -r" +
                                    (if (state.rootInstall) "（以 root 安装）" else "") +
                                    "。安装会改动车机系统，请确认 APK 来源可靠、版本与车机匹配。",
                                confirmText = "开始安装",
                                danger = true
                            ) { actions.installApks() }
                        },
                        modifier = Modifier.weight(1.4f),
                        enabled = enabled
                    )
                }
            }
        }

        // ---------- 任意命令 ----------
        item {
            SectionTitle("执行任意命令")
            CardBox {
                Text(
                    text = "命令直接下发车机 shell，rm / dd / reboot 等操作可能造成不可逆损坏。请确认来源可靠。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MiuixTheme.colorScheme.error
                )
                TextField(
                    value = cmdField,
                    onValueChange = { cmdField = it; state.command = it.text },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = "例如：getprop ro.build.version.release",
                    useLabelAsPlaceholder = true,
                    enabled = enabled,
                    minLines = 2,
                    maxLines = 6,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                HintText("快捷命令", Modifier.padding(top = 14.dp, bottom = 6.dp))
                QUICK_COMMANDS.chunked(3).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (label, cmd) ->
                            Button(
                                onClick = {
                                    state.command = cmd
                                    cmdField = TextFieldValue(cmd)
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                                enabled = enabled,
                                minWidth = 0.dp,
                                minHeight = 0.dp,
                                insideMargin = PaddingValues(horizontal = 8.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (enabled) MiuixTheme.colorScheme.onSecondaryVariant
                                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant
                                )
                            }
                        }
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                SuperSwitch(
                    title = "以 root 执行",
                    summary = "su -c '…'，车机需已 root",
                    checked = state.runAsRoot,
                    onCheckedChange = { state.runAsRoot = it },
                    enabled = enabled
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("超时", fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    TextField(
                        value = timeoutField,
                        onValueChange = { timeoutField = it; state.timeoutSec = it.text },
                        modifier = Modifier.width(88.dp),
                        label = "秒",
                        useLabelAsPlaceholder = true,
                        enabled = enabled,
                        singleLine = true
                    )
                    PrimaryButton(
                        "执行命令",
                        onClick = {
                            state.askConfirm(
                                title = "执行这条命令？",
                                message = "命令：${state.command}\n\n" +
                                    (if (state.runAsRoot) "以 root（su -c）执行" else "以普通 shell 执行") +
                                    "，超时 ${state.timeoutSec} 秒。命令直接下发车机 shell，" +
                                    "rm / dd / reboot 等操作可能造成不可逆损坏。",
                                confirmText = "确认执行",
                                danger = true
                            ) { actions.runCommand() }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = enabled
                    )
                }

                // 输出区：标题 + 操作 + 跟随主题的代码背景
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "输出",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.weight(1f))
                    SecondaryButton("复制", onClick = { actions.copyOutput() })
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(
                        "清空",
                        onClick = {
                            state.askConfirm(
                                title = "清空命令输出？",
                                message = "将清除当前输出区全部内容，无法恢复。",
                                confirmText = "清空"
                            ) { actions.clearOutput() }
                        }
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(top = 10.dp)
                        .background(
                            MiuixTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(12.dp)
                        )
                        .verticalScroll(outputScroll)
                        .padding(12.dp)
                ) {
                    Text(
                        text = state.cmdOutput.ifEmpty { "(无输出)" },
                        color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        item {
            HintText(
                "本页全部走系统内 ADB。快捷命令只把文字填进输入框，需再点「执行命令」才会下发。" +
                    "「进 fastboot」执行后车机会重启进 bootloader、ADB 断开，此时可回到刷机页做 " +
                    "fastboot 回读备份（把分区镜像读回手机，只读不写）或从备份恢复；此阶段只能用 USB OTG，不能用 WiFi ADB。",
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            )
        }
    }
}
