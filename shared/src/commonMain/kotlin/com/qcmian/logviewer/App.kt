package com.qcmian.logviewer

import androidx.compose.runtime.Composable
import com.qcmian.logviewer.platform.LogViewerPlatform
import com.qcmian.logviewer.state.AppState
import com.qcmian.logviewer.ui.LogViewerApp

/**
 * 跨平台统一入口：各平台在启动处注入平台能力实现。
 * - 桌面：desktopApp main.kt
 * - Android：MainActivity
 * - iOS：MainViewController.kt
 * - Web：webApp main.kt
 */
@Composable
fun App(platform: LogViewerPlatform, providedApp: AppState? = null) {
    LogViewerApp(platform, providedApp)
}
