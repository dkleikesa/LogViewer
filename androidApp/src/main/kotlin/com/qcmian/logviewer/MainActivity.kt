package com.qcmian.logviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.platform.AndroidBridge
import com.qcmian.logviewer.platform.AndroidPlatform
import com.qcmian.logviewer.state.AppState

/**
 * Android 入口（第三批）：
 * - 系统文件选择器（OpenMultipleDocuments）
 * - 分享面板接收（ACTION_SEND / ACTION_VIEW，S4 现场抓取）
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: AppState

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val cb = AndroidBridge.pendingPick
            AndroidBridge.pendingPick = null
            if (cb != null) {
                val refs = uris.map { FileRef(AndroidPlatform.displayNameOf(this, it), it.toString(), it) }
                cb(refs)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val platform = AndroidPlatform(applicationContext)
        app = obtainApp(platform)
        AndroidBridge.pickLauncher = { openDocuments.launch(arrayOf("*/*")) }
        AndroidBridge.shareOpen = { ref -> app.openFile(ref) }

        handleIntent(intent)

        setContent {
            App(platform, app)
        }
    }

    companion object {
        @Volatile
        private var appRef: AppState? = null

        /** Activity 重建（旋转等）时复用同一应用状态 */
        fun obtainApp(platform: AndroidPlatform): AppState =
            appRef ?: synchronized(this) {
                appRef ?: AppState(platform).also { appRef = it }
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) {
                    AndroidBridge.shareOpen?.invoke(
                        FileRef(AndroidPlatform.displayNameOf(this, stream), stream.toString(), stream),
                    )
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        app.openTextTab("分享导入.log", text)
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                AndroidBridge.shareOpen?.invoke(
                    FileRef(AndroidPlatform.displayNameOf(this, uri), uri.toString(), uri),
                )
            }
        }
    }

    override fun onDestroy() {
        AndroidBridge.pickLauncher = null
        AndroidBridge.shareOpen = null
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    // 预览场景使用空平台实现会崩溃，仅占位
}
