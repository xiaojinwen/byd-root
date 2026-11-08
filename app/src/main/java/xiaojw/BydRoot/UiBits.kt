package xiaojw.BydRoot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ------------------------------------------------------------------ *
 * 按钮
 *
 * 不使用 miuix TextButton：它内部 Button 的默认 minHeight=40dp 且上下
 * insideMargin=13dp，一旦外部再套 Modifier.height(32dp) 就会把文字裁掉
 * （v1.3.0 日志栏按钮「看不见字」的根因）。这里统一用 Button 显式指定
 * 紧凑尺寸，并给 Text 显式着色，任何主题/禁用态下文字都可见。
 * ------------------------------------------------------------------ */

private val BtnHeight = 40.dp
private val BtnMargin = PaddingValues(horizontal = 14.dp, vertical = 9.dp)

@Composable
private fun BtnText(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun btnModifier(modifier: Modifier) = modifier.heightIn(min = BtnHeight)

/** 主操作按钮（主题主色填充）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = btnModifier(modifier),
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 0.dp,
        insideMargin = BtnMargin,
        colors = ButtonDefaults.buttonColorsPrimary()
    ) {
        BtnText(
            text,
            if (enabled) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.disabledOnPrimaryButton
        )
    }
}

/** 次要按钮（次级填充，与 Card 有对比度）。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = btnModifier(modifier),
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 0.dp,
        insideMargin = BtnMargin,
        colors = ButtonDefaults.buttonColors()
    ) {
        BtnText(
            text,
            if (enabled) MiuixTheme.colorScheme.onSecondaryVariant
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant
        )
    }
}

/** 危险操作按钮（恢复分区这类不可逆动作）。 */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = btnModifier(modifier),
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 0.dp,
        insideMargin = BtnMargin,
        colors = ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.error,
            contentColor = MiuixTheme.colorScheme.onError
        )
    ) {
        BtnText(
            text,
            if (enabled) MiuixTheme.colorScheme.onError
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant
        )
    }
}

/** 分组标题：KernelSU 设置页同款 SmallTitle。 */
@Composable
fun SectionTitle(text: String) {
    SmallTitle(text = text)
}

/** 可折叠分组标题（默认收起，用来收纳高级选项与分步按钮）。 */
@Composable
fun FoldableTitle(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    hint: String = ""
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground
        )
        if (hint.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(text = hint, fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onBackgroundVariant
        )
    }
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MiuixTheme.colorScheme.onBackgroundVariant
    )
}

/** 顶部状态概览：不套 Card，减少一层视觉噪音，让状态成为首屏焦点。 */
@Composable
fun StatusHero(state: AppState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        if (state.busy) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onBackgroundVariant,
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = state.status,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.progress}%",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.size(10.dp))
        LinearProgressIndicator(
            progress = state.progress / 100f,
            modifier = Modifier.fillMaxWidth(),
            height = 6.dp
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = state.progressText,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 底部极简日志条：常驻但只占一行，点击展开日志面板。 */
@Composable
fun LogBar(state: AppState, onOpen: () -> Unit) {
    val last = state.logs.lastOrNull()
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.35f))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onOpen)
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = MiuixIcons.ListView,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MiuixTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = last?.text ?: "暂无运行日志",
                color = last?.color ?: MiuixTheme.colorScheme.onBackgroundVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${state.logs.size} 行",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant
            )
            Icon(
                imageVector = MiuixIcons.ExpandLess,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onBackgroundVariant
            )
        }
    }
}

/**
 * 全屏覆盖的日志面板。按钮用正常尺寸、显式文字颜色，
 * 不再塞进被压缩高度的容器里。两个 Tab 共用同一份日志。
 */
@Composable
fun LogSheet(
    state: AppState,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.size - 1)
    }
    val codeBg = if (state.logs.isEmpty()) MiuixTheme.colorScheme.surfaceContainer
    else MiuixTheme.colorScheme.surfaceContainerHighest

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onClose)
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 必须有明确高度：Column 内 LazyColumn 用了 weight(1f)，
                // 若高度是 wrap-content，剩余空间为 0，日志列表会被压成 0dp。
                .fillMaxHeight(0.72f)
                .heightIn(max = 480.dp)
                .background(
                    MiuixTheme.colorScheme.surface,
                    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "运行日志",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${state.logs.size} 行",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "收起",
                    modifier = Modifier.size(22.dp).clickable(onClick = onClose),
                    tint = MiuixTheme.colorScheme.onBackgroundVariant
                )
            }
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("复制全部", onClick = onCopy, modifier = Modifier.weight(1f))
                SecondaryButton("清空日志", onClick = onClear, modifier = Modifier.weight(1f))
                PrimaryButton("收起", onClick = onClose, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(codeBg, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                items(state.logs) { line ->
                    Text(
                        text = line.text,
                        color = line.color,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** worker 线程 awaitConfirm 的 UI 端：miuix 居中窗口弹窗。 */
@Composable
fun ConfirmHost(state: AppState) {
    val req = state.confirm
    WindowDialog(
        show = req != null,
        title = req?.title ?: "",
        onDismissRequest = { req?.settle(false) }
    ) {
        Text(
            text = req?.message ?: "",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(
                text = "取消",
                onClick = { req?.settle(false) },
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = "继续",
                onClick = { req?.settle(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 所有操作按钮的二次确认弹窗：取消 / 点外部都直接丢弃，确认后才执行动作。 */
@Composable
fun ActionConfirmHost(state: AppState) {
    val req = state.pendingAction
    WindowDialog(
        show = req != null,
        title = req?.title ?: "",
        onDismissRequest = { state.pendingAction = null }
    ) {
        Text(
            text = req?.message ?: "",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MiuixTheme.colorScheme.onBackgroundVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(
                text = "取消",
                onClick = { state.pendingAction = null },
                modifier = Modifier.weight(1f)
            )
            if (req != null && req.danger) {
                DangerButton(
                    text = req.confirmText,
                    onClick = {
                        state.pendingAction = null
                        req.onConfirm()
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                PrimaryButton(
                    text = req?.confirmText ?: "确认执行",
                    onClick = {
                        state.pendingAction = null
                        req?.onConfirm()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 统一的卡片外边距，避免每处手写 padding 叠加。 */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content
    )
}
