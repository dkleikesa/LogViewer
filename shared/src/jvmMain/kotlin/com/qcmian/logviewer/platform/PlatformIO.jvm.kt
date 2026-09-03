package com.qcmian.logviewer.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.PlatformKind
import kotlinx.io.buffered
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.RandomAccessFile
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/** 桌面（JVM）平台能力实现 */
class JvmPlatform : LogViewerPlatform {
    override val kind: PlatformKind = PlatformKind.DESKTOP
    override val usesCommandKey: Boolean = System.getProperty("os.name")?.contains("Mac") == true

    init {
        // 自动化验证：LOGVIEWER_AUTOOPEN=路径 → 启动即打开；诊断日志写文件（stdout 缓冲在进程被杀时丢失）
        AutoOpenPath.value = System.getenv("LOGVIEWER_AUTOOPEN")
        val diagFile = java.io.File("/tmp/lv-diag2.log")
        diagFile.delete()
        DiagLog.write = { s -> runCatching { diagFile.appendText(s) } }
    }

    private val prefsNode: Preferences by lazy {
        Preferences.userNodeForPackage(JvmPlatform::class.java)
    }

    override fun pickFiles(onResult: (List<FileRef>) -> Unit) {
        swingBlocking {
            val dialog = FileDialog(null as Frame?, "打开日志文件", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.setFilenameFilter { _, name -> !name.startsWith(".") }
            dialog.isVisible = true
            val files = dialog.files ?: arrayOf()
            onResult(files.map { FileRef(it.name, it.absolutePath, it.absolutePath) })
        }
    }

    // openReader 使用 common 默认实现（内存字节/文件路径两类引用）

    override fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit) {
        swingBlocking {
            val dialog = FileDialog(null as Frame?, "保存", FileDialog.SAVE)
            dialog.file = defaultName
            dialog.isVisible = true
            val dir = dialog.directory
            val name = dialog.file
            if (dir == null || name == null) {
                onDone(false)
            } else {
                try {
                    File(dir, name).writeText(content, Charsets.UTF_8)
                    onDone(true)
                } catch (e: Exception) {
                    onDone(false)
                }
            }
        }
    }

    override fun setClipboard(text: String, html: String?) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        if (html != null) {
            clipboard.setContents(HtmlTextTransferable(text, html), null)
        } else {
            clipboard.setContents(StringSelection(text), null)
        }
    }

    override fun getClipboardText(): String? = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }

    override fun putPref(key: String, value: String) {
        prefsNode.put(key, value)
    }

    override fun getPref(key: String): String? = prefsNode.get(key, null)

    private class HtmlTextTransferable(private val text: String, private val html: String) : Transferable {
        private val htmlFlavor = try {
            DataFlavor("text/html;class=java.lang.String;charset=utf-8")
        } catch (_: Exception) {
            null
        }

        override fun getTransferDataFlavors(): Array<DataFlavor> =
            if (htmlFlavor != null) arrayOf(DataFlavor.stringFlavor, htmlFlavor)
            else arrayOf(DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean =
            flavor == DataFlavor.stringFlavor || flavor == htmlFlavor

        override fun getTransferData(flavor: DataFlavor?): Any = when (flavor) {
            DataFlavor.stringFlavor -> text
            htmlFlavor -> html
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    companion object {
        /** 在 AWT EDT 上执行模态文件对话框 */
        private fun swingBlocking(block: () -> Unit) {
            if (SwingUtilities.isEventDispatchThread()) {
                block()
            } else {
                SwingUtilities.invokeAndWait { block() }
            }
        }
    }
}

/** JVM：kotlinx-io files 打开本地路径 */
actual fun openPathSource(path: String): OpenedLogSource? = runCatching {
    val p = kotlinx.io.files.Path(path)
    val size = kotlinx.io.files.SystemFileSystem.metadataOrNull(p)?.size ?: return null
    OpenedLogSource(
        source = kotlinx.io.files.SystemFileSystem.source(p).buffered(),
        sizeBytes = size,
    )
}.getOrNull()

/** JVM 时间戳 */
actual fun nowMillis(): Long = System.currentTimeMillis()

/** 桌面快捷键规范化：基于 Key.keyCode（JVM 上为 AWT 键码）全键位覆盖 */
actual fun normalizeKeyEvent(e: KeyEvent): String? {
    if (e.type != KeyEventType.KeyDown) return null
    val code = try {
        e.key.keyCode.toInt()
    } catch (_: Exception) {
        return null
    }
    val label: String = when (code) {
        java.awt.event.KeyEvent.VK_ENTER -> "enter"
        java.awt.event.KeyEvent.VK_ESCAPE -> "escape"
        java.awt.event.KeyEvent.VK_TAB -> "tab"
        java.awt.event.KeyEvent.VK_SPACE -> "space"
        java.awt.event.KeyEvent.VK_PLUS, java.awt.event.KeyEvent.VK_EQUALS,
        java.awt.event.KeyEvent.VK_ADD, java.awt.event.KeyEvent.VK_SEMICOLON,
        -> "plus"
        java.awt.event.KeyEvent.VK_MINUS, java.awt.event.KeyEvent.VK_SUBTRACT -> "minus"
        java.awt.event.KeyEvent.VK_COMMA -> "comma"
        java.awt.event.KeyEvent.VK_PERIOD -> "period"
        java.awt.event.KeyEvent.VK_BACK_SLASH -> "backslash"
        in java.awt.event.KeyEvent.VK_0..java.awt.event.KeyEvent.VK_9 ->
            ('0' + code - java.awt.event.KeyEvent.VK_0).toString()
        in java.awt.event.KeyEvent.VK_NUMPAD0..java.awt.event.KeyEvent.VK_NUMPAD9 ->
            ('0' + code - java.awt.event.KeyEvent.VK_NUMPAD0).toString()
        in java.awt.event.KeyEvent.VK_A..java.awt.event.KeyEvent.VK_Z ->
            ('a' + code - java.awt.event.KeyEvent.VK_A).toString()
        in java.awt.event.KeyEvent.VK_F1..java.awt.event.KeyEvent.VK_F12 ->
            "f" + (code - java.awt.event.KeyEvent.VK_F1 + 1)
        else -> return null
    }
    val parts = ArrayList<String>(3)
    if (e.isCtrlPressed || e.isMetaPressed) parts.add("ctrl")
    if (e.isAltPressed) parts.add("alt")
    if (e.isShiftPressed) parts.add("shift")
    parts.add(label)
    return parts.joinToString("+").lowercase()
}

/** 上下调整光标（AWT N_RESIZE） */
actual fun androidx.compose.ui.Modifier.nsResizeCursor(): androidx.compose.ui.Modifier =
    this.pointerHoverIcon(
        androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)),
    )

/** 桌面右键：Initial pass 观察按下即触发，携带相对组件位置（菜单跟随鼠标） */
actual fun Modifier.secondaryClick(onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.buttons.isSecondaryPressed) {
                onSecondary(event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero)
            }
        }
    }

/** 桌面拖动多选：主键按下拖过 slop 后，按下行 → 当前行区间选中（Initial pass，不干扰单击/右键） */
actual fun Modifier.dragSelect(
    state: com.qcmian.logviewer.state.ViewerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val first = awaitPointerEvent(PointerEventPass.Initial)
        if (!first.buttons.isPrimaryPressed) return@awaitEachGesture
        // 修饰键按住时走行点击语义（shift 连选/ctrl 点选），不进入拖选
        if (com.qcmian.logviewer.platform.KeyboardState.shift || com.qcmian.logviewer.platform.KeyboardState.ctrl) return@awaitEachGesture
        fun posAtY(y: Float): Int {
            val infos = listState.layoutInfo.visibleItemsInfo
            if (infos.isEmpty()) return -1
            val hit = infos.firstOrNull { y >= it.offset && y < it.offset + it.size }
                ?: if (y < infos.first().offset) infos.first() else infos.last()
            return hit.index
        }
        val downY = first.changes.firstOrNull()?.position?.y ?: return@awaitEachGesture
        val anchor = posAtY(downY)
        if (anchor < 0) return@awaitEachGesture
        var dragging = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val change = e.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val y = change.position.y
            if (!dragging && kotlin.math.abs(y - downY) > viewConfiguration.touchSlop) {
                dragging = true
            }
            if (dragging) {
                val cur = posAtY(y)
                if (cur >= 0) state.selectRange(anchor, cur)
            }
        }
    }
}
