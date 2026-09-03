package com.qcmian.logviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.qcmian.logviewer.core.model.PlatformKind
import com.qcmian.logviewer.platform.KeyboardState
import com.qcmian.logviewer.platform.LogViewerPlatform
import com.qcmian.logviewer.state.AppState
import com.qcmian.logviewer.ui.desktop.LogViewerDesktop
import com.qcmian.logviewer.ui.mobile.MobileUi
import com.qcmian.logviewer.ui.theme.LogViewerTheme

/**
 * 应用根入口：按平台选择布局。
 * 桌面（含 Web 宽屏）走双窗格工作台；移动端走底部抽屉布局。
 */
@Composable
fun LogViewerApp(platform: LogViewerPlatform, providedApp: AppState? = null) {
    val app = providedApp ?: remember { AppState(platform) }

    DisposableEffect(app) {
        onDispose {
            app.saveSessions()
        }
    }

    // 全局修饰键跟踪（Ctrl/Cmd+点击多选、Shift 连选）
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                KeyboardState.ctrl = e.isCtrlPressed || e.isMetaPressed
                KeyboardState.shift = e.isShiftPressed
                false
            },
    ) {
        LogViewerTheme(app.prefs.theme) {
            when (platform.kind) {
                PlatformKind.DESKTOP, PlatformKind.WEB -> LogViewerDesktop(app)
                PlatformKind.ANDROID, PlatformKind.IOS -> MobileUi(app)
            }
        }
    }
}
