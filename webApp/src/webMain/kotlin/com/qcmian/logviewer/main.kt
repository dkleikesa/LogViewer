package com.qcmian.logviewer

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.qcmian.logviewer.platform.WasmPlatform

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        App(WasmPlatform())
    }
}
