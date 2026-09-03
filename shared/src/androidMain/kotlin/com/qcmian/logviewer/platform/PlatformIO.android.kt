package com.qcmian.logviewer.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.input.key.KeyEvent
import com.qcmian.logviewer.core.doc.TextEncoding
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.PlatformKind
import kotlinx.io.asSource
import kotlinx.io.buffered

/** Android 平台桥：Activity 侧注册系统选择器并注入回调 */
object AndroidBridge {
    var pendingPick: ((List<FileRef>) -> Unit)? = null
    var pickLauncher: (() -> Unit)? = null
    var shareOpen: ((FileRef) -> Unit)? = null
    var pasteText: (() -> String?)? = null
}

/** Android 平台能力实现（S2/S4：系统选择器 + 分享面板接收） */
class AndroidPlatform(private val context: Context) : LogViewerPlatform {
    override val kind: PlatformKind = PlatformKind.ANDROID

    override fun pickFiles(onResult: (List<FileRef>) -> Unit) {
        AndroidBridge.pendingPick = onResult
        val launcher = AndroidBridge.pickLauncher
        if (launcher == null) {
            onResult(emptyList())
        } else {
            launcher()
        }
    }

    override fun openReader(ref: FileRef): OpenedLogSource? {
        return try {
            val uri = ref.platformData as? Uri ?: Uri.parse(ref.uri)
            val ins = context.contentResolver.openInputStream(uri) ?: return null
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            if (size > 0) {
                // SAF 流式：InputStream → kotlinx-io Source
                OpenedLogSource(source = ins.asSource().buffered(), sizeBytes = size)
            } else {
                // 拿不到长度（云盘等）：退化为全读内存
                val bytes = ins.use { it.readBytes() }
                OpenedLogSource(
                    source = kotlinx.io.Buffer().apply { write(bytes) },
                    sizeBytes = bytes.size.toLong(),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
    override fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit) {
        // Android 走系统分享（CreateDocument 由 Activity 侧注册；此处退化为分享文本）
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, content)
                putExtra(android.content.Intent.EXTRA_TITLE, defaultName)
            }
            val chooser = android.content.Intent.createChooser(intent, "导出：$defaultName")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            onDone(true)
        } catch (_: Exception) {
            onDone(false)
        }
    }

    override fun setClipboard(text: String, html: String?) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("logviewer", text))
    }

    override fun getClipboardText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    override fun putPref(key: String, value: String) {
        context.getSharedPreferences("logviewer", Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    override fun getPref(key: String): String? =
        context.getSharedPreferences("logviewer", Context.MODE_PRIVATE).getString(key, null)

    companion object {
        /** 查询 ContentResolver 显示名 */
        fun displayNameOf(context: Context, uri: Uri): String {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
            return uri.lastPathSegment ?: uri.toString()
        }
    }
}

/** Android：本地路径也走 kotlinx-io files（SAF 内容由 openReader 覆写处理） */
actual fun openPathSource(path: String): OpenedLogSource? = runCatching {
    val p = kotlinx.io.files.Path(path)
    val size = kotlinx.io.files.SystemFileSystem.metadataOrNull(p)?.size ?: return null
    OpenedLogSource(
        source = kotlinx.io.files.SystemFileSystem.source(p).buffered(),
        sizeBytes = size,
    )
}.getOrNull()

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun normalizeKeyEvent(e: KeyEvent): String? = null // 移动端无物理快捷键体系

/** 移动端无右键：长按菜单由 combinedClickable 承担 */
actual fun androidx.compose.ui.Modifier.secondaryClick(onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit): androidx.compose.ui.Modifier = this

actual fun androidx.compose.ui.Modifier.dragSelect(state: com.qcmian.logviewer.state.ViewerState, listState: androidx.compose.foundation.lazy.LazyListState): androidx.compose.ui.Modifier = this

actual fun androidx.compose.ui.Modifier.nsResizeCursor(): androidx.compose.ui.Modifier = this
