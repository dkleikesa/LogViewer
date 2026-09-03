package com.qcmian.logviewer.platform

import androidx.compose.ui.input.key.KeyEvent
import com.qcmian.logviewer.core.doc.toByteArrayCompat
import com.qcmian.logviewer.core.doc.toNSDataCompat
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.PlatformKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/** iOS 平台能力实现（第三批：文件选择 + 分享接收 + 系统导出） */
@OptIn(ExperimentalForeignApi::class)
class IosPlatform : LogViewerPlatform {

    override val kind: PlatformKind = PlatformKind.IOS

    private var pendingPick: ((List<FileRef>) -> Unit)? = null

    /** ObjC delegate 必须独立于 Kotlin 接口实现 */
    private inner class PickerDelegate : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(
            controller: UIDocumentPickerViewController,
            didPickDocumentsAtURLs: List<*>,
        ) {
            val cb = pendingPick ?: return
            pendingPick = null
            val refs = didPickDocumentsAtURLs.mapNotNull { url ->
                (url as? NSURL)?.let { nsUrl ->
                    val path = nsUrl.path ?: return@let null
                    FileRef(
                        nsUrl.lastPathComponent ?: path,
                        nsUrl.absoluteString ?: path,
                        path,
                    )
                }
            }
            cb(refs)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            pendingPick?.invoke(emptyList())
            pendingPick = null
        }
    }

    override fun pickFiles(onResult: (List<FileRef>) -> Unit) {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.data", "public.text"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.delegate = PickerDelegate()
        pendingPick = onResult
        rootViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    // openReader 使用 common 默认实现（路径经 openPathSource 打开）

    override fun saveTextFile(defaultName: String, content: String, onDone: (Boolean) -> Unit) {
        try {
            val path = NSTemporaryDirectory() + defaultName
            val written = platform.Foundation.NSFileManager.defaultManager()
                .createFileAtPath(path, contents = content.encodeToByteArray().toNSDataCompat(), attributes = null)
            if (written) {
                val url = NSURL.fileURLWithPath(path)
                val activity = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
                rootViewController()?.presentViewController(activity, animated = true, completion = null)
                onDone(true)
            } else {
                onDone(false)
            }
        } catch (_: Exception) {
            onDone(false)
        }
    }

    override fun setClipboard(text: String, html: String?) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun getClipboardText(): String? = UIPasteboard.generalPasteboard.string

    override fun putPref(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = key)
    }

    override fun getPref(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key)

    private fun rootViewController(): UIViewController? {
        val window = UIApplication.sharedApplication.keyWindow
            ?: (UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow)
        return window?.rootViewController
    }
}

/** iOS：kotlinx-io files（native 实现）打开本地路径 */
actual fun openPathSource(path: String): OpenedLogSource? = runCatching {
    val p = kotlinx.io.files.Path(path)
    val size = kotlinx.io.files.SystemFileSystem.metadataOrNull(p)?.size ?: return null
    OpenedLogSource(
        source = kotlinx.io.files.SystemFileSystem.source(p).buffered(),
        sizeBytes = size,
    )
}.getOrNull()

actual fun nowMillis(): Long {
    // CFAbsoluteTime 以 2001-01-01 为纪元；978307200 = 与 Unix 纪元的秒差
    val t = platform.CoreFoundation.CFAbsoluteTimeGetCurrent()
    return ((t + 978307200.0) * 1000.0).toLong()
}

actual fun normalizeKeyEvent(e: KeyEvent): String? = null // iOS 无桌面快捷键体系

/** 移动端无右键：长按菜单由 combinedClickable 承担 */
actual fun androidx.compose.ui.Modifier.secondaryClick(onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit): androidx.compose.ui.Modifier = this

actual fun androidx.compose.ui.Modifier.dragSelect(state: com.qcmian.logviewer.state.ViewerState, listState: androidx.compose.foundation.lazy.LazyListState): androidx.compose.ui.Modifier = this

actual fun androidx.compose.ui.Modifier.nsResizeCursor(): androidx.compose.ui.Modifier = this
