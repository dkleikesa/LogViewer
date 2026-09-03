package com.qcmian.logviewer

import androidx.compose.ui.window.ComposeUIViewController
import com.qcmian.logviewer.platform.IosPlatform
import com.qcmian.logviewer.platform.LogViewerPlatform

fun MainViewController(platform: LogViewerPlatform = IosPlatform()) =
    ComposeUIViewController { App(platform) }
