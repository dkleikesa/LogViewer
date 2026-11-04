package com.qcmian.logviewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qcmian.logviewer.core.model.DisplayMode
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.platform.JvmPlatform
import com.qcmian.logviewer.platform.LocalAppWindow
import com.qcmian.logviewer.state.AppState
import com.qcmian.logviewer.ui.LogViewerApp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.net.URI
import kotlinx.coroutines.launch

/**
 * 从拖拽的 Transferable 解析文件列表。
 *
 * 平台差异（拖拽打开失效的根因，2026-09 实测确认）：
 * - Windows / Linux：AWT 提供 [DataFlavor.javaFileListFlavor]
 * - macOS（JBR / Finder）：只提供 "text/uri-list"，不提供 javaFileListFlavor，
 *   需解析 uri-list 文本中的 file: URI
 */
private fun Transferable.readDroppedFiles(): List<File> {
    // 1) 标准文件列表（Windows/Linux）
    runCatching {
        @Suppress("UNCHECKED_CAST")
        (getTransferData(DataFlavor.javaFileListFlavor) as? List<File>)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    // 2) text/uri-list（macOS）
    val uriListFlavor = runCatching {
        DataFlavor("text/uri-list;class=java.lang.String")
    }.getOrNull()
    if (uriListFlavor != null && isDataFlavorSupported(uriListFlavor)) {
        runCatching {
            val text = getTransferData(uriListFlavor) as? String ?: return@runCatching
            val files = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("file:") }
                .mapNotNull { line -> runCatching { File(URI(line)) }.getOrNull() }
                .toList()
            if (files.isNotEmpty()) return files
        }
    }
    return emptyList()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    if (args.firstOrNull() == "bench") {
        Bench.run(args.drop(1))
        return
    }
    // Dock/任务栏图标（裸 JVM 进程场景：gradle run 无 app bundle，macOS Dock 默认显示 Compose 图标）
    if (java.awt.Taskbar.isTaskbarSupported()) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit() // 强制 AWT 初始化（Taskbar 依赖）
            val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("icons/icon-256.png")
            println("[dock-icon] resource stream: $stream")
            if (stream != null) {
                val img = javax.imageio.ImageIO.read(stream)
                println("[dock-icon] image: ${img?.width}x${img?.height}")
                if (img != null) {
                    java.awt.Taskbar.getTaskbar().setIconImage(img)
                    println("[dock-icon] setIconImage ok")
                }
            }
        }.onFailure { println("[dock-icon] failed: $it") }
    }
    application {
    val platform = remember { JvmPlatform() }
    val app = remember { AppState(platform) }

    Window(
        onCloseRequest = {
            app.saveSessions()
            exitApplication()
        },
        // 空标题：Chrome 式标题栏下窗口标题文字会遮挡文件 tab，去掉（Dock/前进后退已足够标识）
        title = "",
        icon = androidx.compose.ui.res.painterResource("icons/icon-256.png"),
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
    ) {
        // Chrome 式标题栏：macOS 内容延伸到标题栏区域，系统交通灯（红黄绿）叠加在 Tab 栏左侧
        val isMac = remember {
            System.getProperty("os.name")?.lowercase()?.contains("mac") == true
        }
        DisposableEffect(window, isMac) {
            if (isMac) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            }
            onDispose { }
        }

        // 桌面菜单栏（macOS 顶部菜单 / Windows 窗口菜单）
        MenuBar {
            Menu("文件") {
                Item("打开文件…") { platform.pickFiles { refs -> refs.forEach { app.openFile(it) } } }
                Item("新建标签页") { app.newEmptyTab() }
                Item("关闭当前标签") { app.closeTab(app.activeTabId) }
                Separator()
                Item("粘贴导入") { app.importFromClipboard() }
                Item("重新加载（保留规则与位置）") { app.active?.reload() }
            }
            Menu("编辑") {
                Item("复制选中行") {
                    val st = app.active ?: return@Item
                    app.scope.launch {
                        app.platform.setClipboard(st.copyLinesText(st.selectedLines.toList()))
                    }
                }
                Item("复制全部文本") {
                    val st = app.active ?: return@Item
                    app.scope.launch {
                        app.platform.setClipboard(st.copyLinesText((0 until st.lineCount).toList()))
                    }
                }
            }
            Menu("视图") {
                Item("切换显示模式") {
                    val st = app.active ?: return@Item
                    st.displayMode = when (st.displayMode) {
                        DisplayMode.SHOW_ALL -> DisplayMode.SHOW_ONLY_FILTERED
                        DisplayMode.SHOW_ONLY_FILTERED -> DisplayMode.SHOW_ALL
                    }
                }
                Item("放大字号") { app.updatePrefs { it.copy(fontSize = (it.fontSize + 1).coerceIn(8, 30)) } }
                Item("缩小字号") { app.updatePrefs { it.copy(fontSize = (it.fontSize - 1).coerceIn(8, 30)) } }
                Item("自动换行") { app.updatePrefs { it.copy(wrapLines = !it.wrapLines) } }
                Item("切换主题（系统/浅色/深色）") {
                    val order = listOf("system", "light", "dark")
                    app.updatePrefs { it.copy(theme = order[(order.indexOf(it.theme) + 1) % order.size]) }
                }
            }
            Menu("导航") {
                Item("过滤命中下一处") { app.active?.jumpFilterMatch(1) }
                Item("过滤命中上一处") { app.active?.jumpFilterMatch(-1) }
                Item("下一个标记行") { app.active?.jumpMarker(1) }
                Item("上一个标记行") { app.active?.jumpMarker(-1) }
                Item("后退（跳转历史）") { app.active?.goBack() }
                Item("前进（跳转历史）") { app.active?.goForward() }
            }
        }

        // 拖拽文件到窗口直接打开（F1-1）：Compose dragAndDropTarget + 多 flavor 降级解析
        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = runCatching { event.awtTransferable.readDroppedFiles() }.getOrDefault(emptyList())
                    if (files.isNotEmpty()) {
                        app.scope.launch {
                            files.filter { it.isFile }.forEach { f ->
                                app.openFile(FileRef(f.name, f.absolutePath, f.absolutePath), newTab = true)
                            }
                        }
                    }
                    return true
                }
            }
        }

        CompositionLocalProvider(LocalAppWindow provides window) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dragAndDropTarget(
                        // 不能只检查 javaFileListFlavor：macOS 上拖拽只提供 text/uri-list，
                        // 条件拒绝会导致整个拖拽会话被 rejectDrag，onDrop 无法触发
                        shouldStartDragAndDrop = { true },
                        target = dropTarget,
                    ),
            ) {
                LogViewerApp(platform, app)
            }
        }
    }
}
}
