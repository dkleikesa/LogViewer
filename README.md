# LogViewer · 本地优先的跨端日志排障工作台

基于 **Kotlin Multiplatform + Compose Multiplatform**，一套共享代码覆盖桌面（macOS/Windows/Linux）、Android、iOS 与 Web。

## 运行

```bash
# 桌面（核心体验）
./gradlew :desktopApp:run

# Android
./gradlew :androidApp:assembleDebug

# Web（P2 基础版）
./gradlew :webApp:wasmJsBrowserDevelopmentRun

# iOS：iosApp 目录用 Xcode 打开运行
```

> 网络受限环境已内置阿里云/华为云镜像（`settings.gradle.kts`）。

## 交付范围（对齐 docs/需求文档.md v0.6，四批一次性交付）

### 第一批 · MVP 桌面
- 文件打开（系统选择器 + 拖拽到窗口）、粘贴导入、内置示例日志
- 编码：UTF-8 / UTF-16 LE/BE / GBK，BOM + 启发式自动检测，失败可手动选择
- 行视图：标记列 | 原始行号 | 级别 | 文本；虚拟滚动（十万行+流畅）
- 长文本原则：显示截断、数据完整（搜索/过滤/复制基于完整行）
- 三种显示模式：全部显示 / 高亮命中（未命中变暗）/ 仅显示命中（Ctrl+H）
- Filter 系统：Include（无启用 Include 时全量进入候选）/ Exclude / 文本·正则·按标记、大小写开关、着色优先级（列表顺序，↑↓ 调序）、命中统计
- Find：独立于 Filter，文本/正则、大小写、范围（当前结果/全文件）、n/m 计数、命中高亮跳转、历史、一键转规则
- 行操作菜单（右键）：复制行/匹配片段、以此为条件建规则、标记、复制定位证据
- 状态栏：文件名、行数、大小、编码、当前行、命中/总数

### 第二批 · 桌面排障工作台
- 日志字段识别（logcat 新旧格式、通用时间戳+级别、Tag、PID/TID）
- 详情侧栏：字段网格、原始内容、JSON 格式化视图
- 关联定位：TraceID/RequestID/Tag 一键建临时过滤器
- 上下文窗口（F9-4）：命中行 ±N 行，上下文行视觉区分
- Marker：8 色标记、一行多标、Ctrl+1~8、标记跳转、按标记筛选
- 快速导航：F8/Shift+F8 过滤命中跳转、Ctrl+G 行号跳转、回到跳转前位置
- 会话/最近文件：本地自动保存与恢复（过滤器/标记/查找/滚动位置）
- Copy/Export：选中/视图/全部复制，TXT/CSV/JSON/HTML(带颜色) 导出，导出前显示行数与范围
- 过滤器集合保存/加载/导入/导出（JSON 跨平台）、日志级别预设
- 偏好设置：主题（跟随系统/浅/深）、字号缩放、行号/标记列/级别列、换行模式、默认显示模式
- 重新加载（F5）保留规则与位置；GBK（桌面/Android/iOS）

### 第三批 · 移动端（Android / iOS）
- 系统文件选择器；分享面板/文件管理器直接送入（S4 现场抓取）
- 移动交互：底部抽屉（规则/详情/设置）、底部导航、长按行菜单、可展开搜索
- 导出走系统分享（Android Intent / iOS UIActivityViewController）

### 第四批 · 增强
- 实时跟随（Tail）：文件追加增量读取、自动滚动、未读条数、回到底部（桌面 InMemory 文档）
- 多标签页：Tab 切换、会话与过滤状态按文件隔离、关闭恢复
- CSV/TSV 列模式：检测后切换列视图、表头排序、列过滤
- 多文件聚合：合并所有标签为聚合视图浏览
- 脱敏：可配置规则（手机号/邮箱/Token/Cookie/IP/自定义正则），复制导出前应用、不修改原始文件
- 快捷键自定义：偏好设置中录制修改
- Web（wasmJs/JS）：文件选择、查看/过滤/搜索/导出（基础版）

## 大文件（4.1）

逻辑完整加载、物理按需缓存：桌面 >32MB 文件走行偏移索引 + 按需解码（5GB+ 可用，打开时显示索引进度）；其余平台全内存。

## 工程结构

```
shared/src/commonMain  核心引擎 + 状态 + 桌面/移动双布局 UI
  core/      model(模型) doc(文档/编码/索引) engine(过滤/搜索) parse(日志/JSON/CSV) sanitize(脱敏) export(导出)
  state/     ViewerState(单文件) AppState(多标签/会话/偏好)
  platform/  LogViewerPlatform 接口 + expect 声明
  ui/        theme / desktop(双窗格) / mobile(底部抽屉) / common(对话框)
shared/src/jvmMain       桌面实现：AWT 对话框、剪贴板(含HTML)、GBK、大文件索引、右键、快捷键规范化
shared/src/androidMain   Android 实现（Context 注入）
shared/src/iosMain       iOS 实现（UIDocumentPicker / NSFileManager / GB18030）
shared/src/wasmJsMain,jsMain  Web 实现（index.html JS 桥）
desktopApp / androidApp / iosApp / webApp   各端入口
```

## 约定

- 界面语言仅中文（D3）；无网络上报，所有数据留在设备本地（4.4）
- 原始文本为唯一可信底座：解析失败一律回退原始行，不改变内容与行号（D6）
