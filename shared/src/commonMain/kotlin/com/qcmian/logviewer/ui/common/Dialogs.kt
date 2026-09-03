package com.qcmian.logviewer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qcmian.logviewer.core.doc.TextEncoding
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.MatchMode
import com.qcmian.logviewer.core.model.MarkerPalette
import com.qcmian.logviewer.core.model.ShortcutAction
import com.qcmian.logviewer.core.model.AppPreferences
import com.qcmian.logviewer.ui.theme.LocalLvColors
import com.qcmian.logviewer.ui.theme.toComposeColor

/* ==================== 通用对话框外壳 ==================== */

@Composable
fun DialogShell(
    title: String,
    onDismiss: () -> Unit,
    width: Int = 460,
    height: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalLvColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(width.dp)
                .then(if (height != null) Modifier.height(height.dp) else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
                Spacer(Modifier.weight(1f))
                Text(
                    "✕",
                    color = c.textFaint,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val c = LocalLvColors.current
    Text(text, color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp, top = 8.dp))
}

@Composable
private fun LvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalLvColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = c.text,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceAlt,
            unfocusedContainerColor = c.surfaceAlt,
            focusedIndicatorColor = c.accent,
            unfocusedIndicatorColor = c.border,
            cursorColor = c.accent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalLvColors.current
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.surfaceAlt)
            .padding(2.dp),
    ) {
        options.forEachIndexed { i, opt ->
            Text(
                opt,
                color = if (i == selected) c.text else c.textDim,
                fontSize = 10.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i == selected) c.surfaceHigh else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

/* ==================== 编码选择（F1-3） ==================== */

@Composable
fun EncodingDialog(
    onDismiss: () -> Unit,
    onConfirm: (TextEncoding) -> Unit,
) {
    val c = LocalLvColors.current
    DialogShell("选择文件编码", onDismiss, width = 380) {
        Text(
            "无法自动识别该文件的编码。\n中文 Windows 导出的日志常见为 GBK。",
            color = c.textDim, fontSize = 12.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextEncoding.entries.forEach { enc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(c.surfaceAlt)
                        .clickable { onConfirm(enc) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(enc.zh, color = c.text, fontSize = 12.5.sp)
                }
            }
        }
    }
}

/* ==================== 跳转行号（F6-3） ==================== */

@Composable
fun GoToLineDialog(
    maxLine: Int,
    currentLine: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    val c = LocalLvColors.current
    var value by remember { mutableStateOf((currentLine + 1).toString()) }
    DialogShell("跳转到行号（Ctrl+G）", onDismiss, width = 340) {
        Text(
            "共 ${com.qcmian.logviewer.core.export.Exporter.formatCount(maxLine)} 行，当前第 ${currentLine + 1} 行",
            color = c.textDim, fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        LvTextField(value, { v -> value = v.filter { it.isDigit() } }, mono = true)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            MiniButton("取消", onClick = onDismiss)
            MiniButton("跳转", primary = true, onClick = {
                val n = value.toIntOrNull() ?: return@MiniButton
                onGo(n)
            })
        }
    }
}

/* ==================== 导出（F8-3 / F10-2） ==================== */

@Composable
fun ExportDialog(
    viewLineCount: Int,
    totalLines: Int,
    findMatchCount: Int,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onExport: (scopeIdx: Int, format: com.qcmian.logviewer.core.export.ExportFormat, includeLineNumbers: Boolean) -> Unit,
) {
    val c = LocalLvColors.current
    var scope by remember { mutableStateOf(0) }
    var format by remember { mutableStateOf(com.qcmian.logviewer.core.export.ExportFormat.TXT) }
    var includeLineNumbers by remember { mutableStateOf(true) }

    val scopeCount = when (scope) {
        0 -> viewLineCount
        1 -> if (findMatchCount >= 0) findMatchCount else 0
        2 -> if (selectedCount > 0) selectedCount else 1
        else -> totalLines
    }

    DialogShell("导出结果", onDismiss, width = 480) {
        Column {
            FieldLabel("导出范围")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "当前视图（$viewLineCount 行）" to 0,
                    "搜索命中（$findMatchCount 行）" to 1,
                    "选中行（$selectedCount 行）" to 2,
                    "全部文本（$totalLines 行）" to 3,
                ).forEach { (label, idx) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (scope == idx) c.active else c.surfaceAlt)
                            .clickable { scope = idx }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (scope == idx) "●" else "○",
                            color = c.accent, fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(label, color = c.text, fontSize = 12.sp)
                    }
                }
            }
            FieldLabel("格式")
            Segmented(
                options = com.qcmian.logviewer.core.export.ExportFormat.entries.map { it.zh },
                selected = format.ordinal,
            ) { format = com.qcmian.logviewer.core.export.ExportFormat.entries[it] }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(includeLineNumbers, { includeLineNumbers = it }, modifier = Modifier.size(18.dp))
                Text("包含行号", color = c.textDim, fontSize = 11.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "将导出 $scopeCount 行；保存前请确认范围与数量",
                color = c.textFaint, fontSize = 11.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                MiniButton("取消", onClick = onDismiss)
                MiniButton("选择保存位置…", primary = true, onClick = { onExport(scope, format, includeLineNumbers) })
            }
        }
    }
}

/* ==================== 偏好设置（3.9） ==================== */

@Composable
fun PrefsDialog(
    prefs: AppPreferences,
    shortcutMap: com.qcmian.logviewer.core.model.ShortcutMap,
    onDismiss: () -> Unit,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onCaptureShortcut: (ShortcutAction, (String) -> Unit) -> Unit,
) {
    val c = LocalLvColors.current
    var tab by remember { mutableStateOf(0) }

    DialogShell("偏好设置", onDismiss, width = 560, height = 520) {
        Column {
            Segmented(listOf("外观", "行为", "快捷键"), tab) { tab = it }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    0 -> {
                        PrefRow("主题") {
                            Segmented(listOf("跟随系统", "浅色", "深色"), listOf("system", "light", "dark").indexOf(prefs.theme).coerceAtLeast(0)) {
                                onUpdatePrefs { p -> p.copy(theme = listOf("system", "light", "dark")[it]) }
                            }
                        }
                        PrefRow("字号：${prefs.fontSize}pt") {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                MiniButton("－") { onUpdatePrefs { p -> p.copy(fontSize = (p.fontSize - 1).coerceIn(8, 30)) } }
                                MiniButton("＋") { onUpdatePrefs { p -> p.copy(fontSize = (p.fontSize + 1).coerceIn(8, 30)) } }
                                MiniButton("重置") { onUpdatePrefs { p -> p.copy(fontSize = 13) } }
                            }
                        }
                        PrefSwitch("显示行号", prefs.showLineNumbers) { v -> onUpdatePrefs { it.copy(showLineNumbers = v) } }
                        PrefSwitch("显示标记列", prefs.showMarkerColumn) { v -> onUpdatePrefs { it.copy(showMarkerColumn = v) } }
                        PrefSwitch("自动换行（超长行）", prefs.wrapLines) { v -> onUpdatePrefs { it.copy(wrapLines = v) } }
                        PrefSwitch("显示规则工作台", prefs.showWorkbench) { v -> onUpdatePrefs { it.copy(showWorkbench = v) } }
                    }
                    1 -> {
                        PrefRow("默认显示模式") {
                            Segmented(
                                com.qcmian.logviewer.core.model.DisplayMode.entries.map { it.zh },
                                com.qcmian.logviewer.core.model.DisplayMode.entries.indexOf(prefs.defaultDisplayMode).coerceAtLeast(0),
                            ) {
                                onUpdatePrefs { p ->
                                    p.copy(defaultDisplayMode = com.qcmian.logviewer.core.model.DisplayMode.entries[it])
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            "点击「修改」后按下新的组合键（Ctrl/Shift/Alt/字母/F 功能键）。",
                            color = c.textFaint, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        ShortcutAction.entries.forEach { action ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(action.zh, color = c.textDim, fontSize = 11.5.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    formatKeyForDisplay(shortcutMap.keyOf(action)),
                                    color = c.text, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(c.surfaceAlt)
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                MiniButton("修改", onClick = {
                                    onCaptureShortcut(action) { newKey ->
                                        onUpdatePrefs { p ->
                                            p.copy(shortcutOverrides = p.shortcutOverrides + (action.name to newKey))
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Spacer(Modifier.weight(1f))
                MiniButton("完成", primary = true, onClick = onDismiss)
            }
        }
    }
}

private fun formatKeyForDisplay(key: String): String =
    key.replace("ctrl+", if (com.qcmian.logviewer.platform.KeyboardState.ctrl) "Ctrl+" else "Ctrl+")
        .replaceFirstChar { it.uppercase() }

@Composable
private fun PrefRow(label: String, content: @Composable RowScope.() -> Unit) {
    val c = LocalLvColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.text, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        content()
    }
}

@Composable
private fun PrefSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalLvColors.current.text, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/* ==================== 预设选择（F3-8） ==================== */

@Composable
fun PresetsDialog(
    onDismiss: () -> Unit,
    onApply: (which: Int) -> Unit,
) {
    val c = LocalLvColors.current
    DialogShell("日志级别预设", onDismiss, width = 400) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "ERROR / WARN / INFO / DEBUG（替换现有规则）" to 0,
                "Android 崩溃分析集（Crash / ANR / Network）" to 1,
                "在现有规则上追加 ERROR 预设" to 2,
            ).forEach { (label, idx) ->
                Text(
                    label,
                    color = c.text,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(c.surfaceAlt)
                        .clickable { onApply(idx) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                )
            }
        }
    }
}
