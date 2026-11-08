package xiaojw.BydRoot

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 「工具 / 命令」页的纯逻辑：与 UI 组件库无关，只调用已验证的 Java 协议层。 */
object ToolOps {

    fun fmtSize(n: Long): String =
        if (n >= 1024 * 1024) String.format(Locale.US, "%.1f MB", n / 1048576.0)
        else "${n / 1024} KB"

    /** shell 单引号转义：' -> '\''，避免 root 命令里的引号破坏 su -c。 */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun queryMeta(ctx: Context, uri: Uri): ApkItem? {
        val cr = ctx.contentResolver
        var name = "app.apk"
        var size = -1L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nIdx >= 0) c.getString(nIdx)?.let { name = it }
                    if (sIdx >= 0 && !c.isNull(sIdx)) size = c.getLong(sIdx)
                }
            }
        } catch (ignored: Exception) { }
        if (size <= 0) size = resolveSize(ctx, uri)
        if (size <= 0) return null
        return ApkItem(uri, name, size)
    }

    /** SAF 拿不到 SIZE 时先试 AssetFileDescriptor，再退回整文件计数，避免 push 长度算错。 */
    fun resolveSize(ctx: Context, uri: Uri): Long {
        val cr = ctx.contentResolver
        try {
            val afd = cr.openAssetFileDescriptor(uri, "r")
            if (afd != null) {
                val n = afd.length
                afd.close()
                if (n > 0) return n
            }
        } catch (ignored: Exception) { }
        return try {
            cr.openInputStream(uri)?.use { input ->
                val buf = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            } ?: -1L
        } catch (e: Exception) { -1L }
    }

    /** 逐个 push 到 /data/local/tmp 后 pm install，最后清理临时文件。 */
    fun installApks(
        ctx: Context,
        ui: UiBridge,
        connect: () -> AdbClient,
        items: List<ApkItem>,
        asRoot: Boolean
    ) {
        ui.setStatus("正在安装 APK")
        val adb = connect()
        try {
            var ok = 0
            items.forEachIndexed { idx, item ->
                if (ui.isCancelled()) return@forEachIndexed
                val size = if (item.size > 0) item.size else resolveSize(ctx, item.uri)
                if (size <= 0) {
                    ui.log("err", "无法取得 ${item.name} 的文件大小，已跳过")
                    return@forEachIndexed
                }
                val safe = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val remote = "/data/local/tmp/BydRoot_$safe"
                ui.log("step", "[${idx + 1}/${items.size}] 推送 ${item.name} (${fmtSize(size)})")
                val progress = object : ProgressSink {
                    override fun onProgress(done: Long, total: Long) {
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        ui.setProgress(pct, "推送 ${item.name} ${fmtSize(done)} / ${fmtSize(total)}")
                    }
                    override fun onMessage(msg: String) { ui.log("info", msg) }
                }
                val input = ctx.contentResolver.openInputStream(item.uri)
                    ?: throw IOException("无法打开 ${item.name}")
                input.use { adb.push(it, size, remote, "0644", progress) }

                val installCmd = "pm install -r $remote"
                val cmd = if (asRoot) "su -c ${shQuote(installCmd)}" else installCmd
                val out = adb.shell(cmd, 180000)
                val success = out.contains("Success", ignoreCase = true)
                ui.log(if (success) "ok" else "err", "${item.name} → ${out.trim().ifEmpty { "(无输出)" }}")
                if (success) ok++
                try { adb.shell("rm -f $remote", 10000) } catch (ignored: Exception) { }
            }
            ui.setProgress(100, "安装完成 $ok/${items.size}")
            ui.setStatus("安装完成 $ok/${items.size}")
            ui.log("ok", "APK 安装结束：成功 $ok / 共 ${items.size}")
        } finally {
            adb.close()
        }
    }

    /** shellStream 实时流式输出，带增量 UTF-8 解码（中文不会被 ADB 分包截断）。 */
    fun runCommand(
        ui: UiBridge,
        connect: () -> AdbClient,
        raw: String,
        asRoot: Boolean,
        timeoutSec: Long,
        append: (String) -> Unit
    ) {
        val cmd = if (asRoot) "su -c ${shQuote(raw)}" else raw
        val sink = StreamTextSink(append)
        append("$ $raw\n${if (asRoot) "(su -c)\n" else ""}────────\n")
        ui.log("step", "执行命令：$cmd")
        ui.setStatus("命令执行中")
        val adb = connect()
        try {
            val n = adb.shellStream(cmd, sink, timeoutSec * 1000L)
            sink.finish()
            append("\n────────\n[结束，共 $n 字节]\n")
            ui.log("ok", "命令结束，输出 $n 字节")
            ui.setStatus("命令结束")
            ui.setProgress(100, "命令结束")
        } finally {
            adb.close()
        }
    }

    /** 增量 UTF-8 解码器：每个 ADB WRTE 包进来就立刻解码，不完整的多字节序列留到下一包。 */
    class StreamTextSink(private val onText: (String) -> Unit) : OutputStream() {
        private val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        private var leftover = ByteArray(0)

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            val fresh = b.copyOfRange(off, off + len)
            val data = if (leftover.isEmpty()) fresh else leftover + fresh
            val inBuf = ByteBuffer.wrap(data)
            while (inBuf.hasRemaining()) {
                val outBuf = CharBuffer.allocate(inBuf.remaining() * 2 + 4)
                val res = decoder.decode(inBuf, outBuf, false)
                outBuf.flip()
                if (outBuf.hasRemaining()) onText(outBuf.toString())
                if (!res.isOverflow) break
            }
            leftover = if (inBuf.hasRemaining()) {
                ByteArray(inBuf.remaining()).also { inBuf.get(it) }
            } else ByteArray(0)
        }

        fun finish() {
            if (leftover.isNotEmpty()) {
                onText(String(leftover, StandardCharsets.UTF_8))
                leftover = ByteArray(0)
            }
        }
    }
}
