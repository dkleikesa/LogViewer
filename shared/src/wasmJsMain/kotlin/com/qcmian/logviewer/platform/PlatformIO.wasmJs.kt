package com.qcmian.logviewer.platform

import androidx.compose.ui.input.key.KeyEvent
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.PlatformKind

/** 由 index.html 注入的平台桥（全部使用简单类型，避免 wasmJs JS interop 差异） */
private external fun __lvSave(name: String, content: String): Boolean
private external fun __lvCopy(text: String): Boolean
private external fun __lvGetPref(key: String): String?
private external fun __lvPutPref(key: String, value: String): Boolean
private external fun __lvPick(onDone: (String) -> Unit)    // 回调返回以 \u0001 分隔的文件名
private external fun __lvFileSize(idx: Int): Int
private external fun __lvReadChunk(idx: Int, offset: Int, len: Int): String // latin1 分块
private external fun __lvClearFiles()
private external fun __lvNow(): Double

/** Web（wasmJs）平台能力实现（第四批基础版） */
class WasmPlatform : LogViewerPlatform {
    override val kind: PlatformKind = PlatformKind.WEB

    override fun pickFiles(onResult: (List<FileRef>) -> Unit) {
        try {
            __lvPick { namesStr ->
                val refs = try {
                    val names = namesStr.split('\u0001').filter { it.isNotEmpty() }
                    val out = ArrayList<FileRef>(names.size)
                    names.forEachIndexed { i, name ->
                        val bytes = readAllChunks(i)
                        out.add(FileRef(name, "web://$name", bytes))
                    }
                    __lvClearFiles()
                    out
                } catch (_: Throwable) {
                    emptyList()
                }
                onResult(refs)
            }
        } catch (_: Throwable) {
            onResult(emptyList())
        }
    }

    private fun readAllChunks(idx: Int): ByteArray {
        val size = __lvFileSize(idx)
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val chunk = __lvReadChunk(idx, offset, 1 shl 20)
            if (chunk.isEmpty()) break
            for (i in chunk.indices) {
                out[offset + i] = chunk[i].code.toByte()
            }
            offset += chunk.length
        }
        return out
    }

    // openReader 使用 common 默认实现（Web 平台 platformData 为内存字节）

    override fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit) {
        onDone(runCatching { __lvSave(defaultName, content) }.getOrDefault(false))
    }

    override fun setClipboard(text: String, html: String?) {
        runCatching { __lvCopy(text) }
    }

    override fun getClipboardText(): String? = null // Web 粘贴导入由系统粘贴承担

    override fun putPref(key: String, value: String) {
        runCatching { __lvPutPref(key, value) }
    }

    override fun getPref(key: String): String? =
        runCatching { __lvGetPref(key) }.getOrNull()
}

/** Web 无文件系统 */
actual fun openPathSource(path: String): OpenedLogSource? = null

actual fun nowMillis(): Long = __lvNow().toLong()

actual fun normalizeKeyEvent(e: KeyEvent): String? = null // Web 快捷键由浏览器默认行为承担

actual fun androidx.compose.ui.Modifier.secondaryClick(onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit): androidx.compose.ui.Modifier = this

actual fun androidx.compose.ui.Modifier.dragSelect(state: com.qcmian.logviewer.state.ViewerState, listState: androidx.compose.foundation.lazy.LazyListState): androidx.compose.ui.Modifier = this

actual fun androidx.compose.ui.Modifier.nsResizeCursor(): androidx.compose.ui.Modifier = this
