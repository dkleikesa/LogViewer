package com.qcmian.logviewer.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon

actual val windowTrafficLightsInset: Boolean = false

/** Web 端回退十字光标 */
actual val colResizeIcon: PointerIcon = PointerIcon.Crosshair

@Composable
actual fun WindowDragRegion(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
