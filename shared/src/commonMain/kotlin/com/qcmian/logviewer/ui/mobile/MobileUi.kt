package com.qcmian.logviewer.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.logviewer.core.export.Exporter
import com.qcmian.logviewer.core.export.ExportFormat
import com.qcmian.logviewer.core.model.DisplayMode
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterPresets
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.MatchMode
import com.qcmian.logviewer.state.AppState
import com.qcmian.logviewer.state.ViewerState
import com.qcmian.logviewer.ui.common.ColorSwatch
import com.qcmian.logviewer.ui.common.MiniButton
import com.qcmian.logviewer.ui.common.Segmented
import com.qcmian.logviewer.ui.desktop.ContextAction
import com.qcmian.logviewer.ui.desktop.LogPane
import com.qcmian.logviewer.ui.theme.LocalLvColors
import com.qcmian.logviewer.ui.theme.toComposeColor
import kotlinx.coroutines.launch

/**
 * 移动端布局（第三批）：
 * 顶栏（打开/更多）+ 可展开搜索 + 日志区 + 底部导航（日志/规则/详情/设置）；
 * 过滤器收入底部抽屉；长按行弹出操作菜单；导出走系统分享。
 */
@Composable
fun MobileUi(app: AppState) {
    val c = LocalLvColors.current
    val active = app.active ?: return
    val scope = rememberCoroutineScope()
    var bottomTab by remember { mutableStateOf(-1) } // -1 隐藏；0 规则 1 设置
    var showFind by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    active.fileName.ifEmpty { "LogViewer" },
                    color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (active.loadPhase == ViewerState.LoadPhase.Ready) {
                    Text(
                        "${Exporter.formatCount(active.lineCount)} 行 · ${active.encodingZh} · ${Exporter.formatSize(active.fileSizeBytes)}",
                        color = c.textFaint, fontSize = 10.sp,
                    )
                }
            }
            MiniButton("🔍", onClick = { showFind = !showFind })
            Spacer(Modifier.width(6.dp))
            MiniButton("打开", onClick = {
                app.platform.pickFiles { refs -> refs.firstOrNull()?.let { app.openFile(it) } }
            }, primary = true)
            Spacer(Modifier.width(6.dp))
            MoreMenu(app, active)
        }

        // 搜索条（移动端简化：文本 + 计数 + 上下个）
        if (showFind) {
            Row(
                Modifier.fillMaxWidth().background(c.surfaceAlt).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = active.findQuery,
                    onValueChange = { active.findQuery = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (active.findMatches == null) "" else "${active.findCursor + 1}/${active.findMatches?.size ?: 0}",
                    color = c.textFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                MiniButton("↑", onClick = { active.findPrev() })
                MiniButton("↓", onClick = { active.findNext() })
                MiniButton(if (active.findRegex) ".*" else "Ab", primary = active.findRegex, onClick = { active.findRegex = !active.findRegex })
            }
            LaunchedEffect(active.findQuery, active.findRegex) {
                kotlinx.coroutines.delay(500)
                if (active.findQuery.isNotEmpty()) active.runFind() else active.clearFind()
            }
        }

        // 日志区（长按菜单）
        Box(Modifier.weight(1f)) {
            if (active.loadPhase == ViewerState.LoadPhase.Ready || active.loadPhase == ViewerState.LoadPhase.Loading) {
                LogPane(
                    state = active,
                    fontSize = app.prefs.fontSize,
                    showLineNumbers = app.prefs.showLineNumbers,
                    showMarkerColumn = app.prefs.showMarkerColumn,
                    wrapLines = true,
                    onContextAction = { action, line ->
                        handleMobileAction(app, active, action, line)
                    },
                )
            } else {
                MobileWelcome(app)
            }
        }

        // 底部抽屉（规则/详情/设置）
        if (bottomTab >= 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(c.surface)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (bottomTab) {
                            0 -> "过滤规则"
                            1 -> "行详情"
                            else -> "设置"
                        },
                        color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("收起", color = c.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { bottomTab = -1 }.padding(6.dp))
                }
                Spacer(Modifier.height(8.dp))
                when (bottomTab) {
                    0 -> MobileFilterSheet(app, active)
                    else -> MobileSettingsSheet(app)
                }
            }
        } else {
            // 底部导航
            Row(
                Modifier.fillMaxWidth().background(c.surface).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomNavItem("日志") { bottomTab = -1 }
                BottomNavItem("规则 ${active.filters.size}") { bottomTab = 0 }
                BottomNavItem("详情") { bottomTab = 1 }
                BottomNavItem("设置") { bottomTab = 2 }
            }
        }
    }
}

@Composable
private fun BottomNavItem(label: String, onClick: () -> Unit) {
    val c = LocalLvColors.current
    Text(
        label,
        color = c.textDim,
        fontSize = 11.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun MoreMenu(app: AppState, state: ViewerState) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        MiniButton("⋯", onClick = { open = !open })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = listOf(
                "加载示例日志" to { app.loadSample() },
                "粘贴导入" to { app.importFromClipboard() },
                "重新加载" to { state.reload() },
                "导出当前视图（分享）" to {
                    scope.launch {
                        val content = state.exportView(
                            ExportFormat.TXT, true,
                        )
                        app.platform.saveTextFile("logviewer-export.txt", content) { }
                    }
                },
                "显示模式" to {
                    state.displayMode = when (state.displayMode) {
                        DisplayMode.SHOW_ALL -> DisplayMode.SHOW_ONLY_FILTERED
                        DisplayMode.SHOW_ONLY_FILTERED -> DisplayMode.SHOW_ALL
                    }
                },
                "新建规则" to { },
                "清空规则" to { state.clearFilters() },
                "清空本地记录" to { app.clearAllLocalData() },
            )
            items.forEach { (label, action) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, fontSize = 13.sp) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

@Composable
private fun MobileWelcome(app: AppState) {
    val c = LocalLvColors.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("LogViewer", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.text)
        Text("移动日志排障", fontSize = 12.sp, color = c.textDim)
        Spacer(Modifier.height(20.dp))
        MiniButton("打开文件", primary = true, onClick = {
            app.platform.pickFiles { refs -> refs.firstOrNull()?.let { app.openFile(it) } }
        })
        Spacer(Modifier.height(10.dp))
        MiniButton("加载示例日志", onClick = { app.loadSample() })
        Spacer(Modifier.height(10.dp))
        MiniButton("从剪贴板粘贴", onClick = { app.importFromClipboard() })
        if (app.recentFiles.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("最近文件", color = c.textFaint, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            app.recentFiles.take(4).forEach { entry ->
                Text(
                    entry.fileName,
                    color = c.textDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.surfaceAlt)
                        .clickable {
                            app.openFile(
                                com.qcmian.logviewer.core.model.FileRef(entry.fileName, entry.fileUri, entry.fileUri),
                            )
                        }
                        .padding(10.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/* ==================== 抽屉内容 ==================== */

@Composable
private fun MobileFilterSheet(app: AppState, state: ViewerState) {
    val c = LocalLvColors.current
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniButton("新建", primary = true, onClick = { state.addFilter(FilterConfig()) })
            MiniButton("预设") {
                state.clearFilters()
                FilterPresets.levelPresets().forEach { state.addFilter(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("显示模式", color = c.textDim, fontSize = 11.sp)
            Segmented(
                options = DisplayMode.entries.map { it.zh },
                selected = DisplayMode.entries.indexOf(state.displayMode),
            ) { state.displayMode = DisplayMode.entries[it] }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "管道：包含 → 排除 → 当前视图（${Exporter.formatCount(state.lineCount)} 行）",
            color = c.textFaint, fontSize = 10.sp,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(state.filters, key = { it.id }) { f ->
                val idx = state.filters.indexOfFirst { it.id == f.id }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorSwatch(f.background ?: f.foreground ?: 0xFF5B8CFFUL.toLong(), 10)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (f.type == FilterType.INCLUDE) "I " else "E ") + f.name +
                            "  \"${f.pattern}\"  [${Exporter.formatCount(state.filterResult?.counts?.getOrNull(idx) ?: 0)}]",
                        color = if (f.enabled) c.text else c.textFaint,
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    MiniButton(if (f.enabled) "关" else "开", primary = f.enabled, onClick = { state.toggleFilter(f.id) })
                    Spacer(Modifier.width(4.dp))
                    MiniButton("删", onClick = { state.removeFilter(f.id) })
                }
            }
        }
    }
}

@Composable
private fun Field(k: String, v: String) {
    val c = LocalLvColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = c.textFaint, fontSize = 10.5.sp, modifier = Modifier.width(70.dp))
        Text(v, color = c.textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MobileSettingsSheet(app: AppState) {
    val c = LocalLvColors.current
    val prefs = app.prefs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingRow("主题") {
            Segmented(listOf("自动", "浅色", "深色"), listOf("system", "light", "dark").indexOf(prefs.theme).coerceAtLeast(0)) { idx ->
                val themes = listOf("system", "light", "dark")
                app.updatePrefs { it.copy(theme = themes[idx]) }
            }
        }
        SettingRow("字号 ${prefs.fontSize}pt") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniButton("－") { app.updatePrefs { it.copy(fontSize = (it.fontSize - 1).coerceIn(8, 26)) } }
                MiniButton("＋") { app.updatePrefs { it.copy(fontSize = (it.fontSize + 1).coerceIn(8, 26)) } }
            }
        }
        SettingToggle("显示行号", prefs.showLineNumbers) { v -> app.updatePrefs { it.copy(showLineNumbers = v) } }
        SettingToggle("显示标记列", prefs.showMarkerColumn) { v -> app.updatePrefs { it.copy(showMarkerColumn = v) } }
        Spacer(Modifier.height(10.dp))
        Text("本产品纯本地处理，无网络上报；会话与最近文件仅保存在本设备。", color = c.textFaint, fontSize = 10.5.sp)
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable RowScope.() -> Unit) {
    val c = LocalLvColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SettingToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LocalLvColors.current.text, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun handleMobileAction(app: AppState, state: ViewerState, action: ContextAction, line: Int) {
    when (action) {
        ContextAction.COPY_LINE -> app.scope.launch {
            app.platform.setClipboard(state.copyLinesText(listOf(line)))
        }
        ContextAction.COPY_SELECTED -> app.scope.launch {
            app.platform.setClipboard(state.copyLinesText(state.selectedLines.sorted().toList()))
        }
        ContextAction.NEW_FILTER_FROM_LINE -> state.quickFilter((state.textOf(line) ?: "").trim().take(120))
        ContextAction.TOGGLE_MARKER -> state.toggleMarker(line, state.activeMarker)
    }
}
