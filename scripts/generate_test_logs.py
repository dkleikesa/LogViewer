#!/usr/bin/env python3
"""LogViewer 测试日志生成器。

按指定大小生成贴近真实场景的混合格式日志文件，用于大文件性能测试
（打开、滚动、过滤、搜索、跳转、导出），内容覆盖需求文档中的典型场景：

- Android Logcat（应用 / 系统进程）
- 服务端日志（logback 风格，带 traceId，可测试 F9-3 关联定位）
- 单行 JSON（可测试 F9-2 JSON 详情）
- syslog 行（未知格式，按原始文本呈现）
- FATAL EXCEPTION 堆栈块 / ANR 块 / GC 事件 / 2~8KB 超长行（F2-4）
- 中英文混合内容（UTF-8）

用法:
    python3 scripts/generate_test_logs.py                           # 生成 10M/100M/500M/1G
    python3 scripts/generate_test_logs.py --sizes 5G                # 5GB 压测文件（需求 4.1）
    python3 scripts/generate_test_logs.py --sizes 10M,100M          # 自定义大小列表
    python3 scripts/generate_test_logs.py --with-encoding-samples   # 额外生成 GBK/UTF-16 编码样本（F1-3）

说明:
- 大小单位为十进制（10M = 10,000,000 字节），与 macOS Finder 显示一致；
- 固定随机种子，结果可复现；不同文件使用不同种子，内容互不相同。
"""
from __future__ import annotations

import argparse
import random
import re
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from string import Formatter

# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------

_UNITS = {"": 1, "k": 10**3, "m": 10**6, "g": 10**9}
MONTHS = ["", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
          "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def parse_size(text: str) -> int:
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([kKmMgG]?)[bB]?", text.strip())
    if not m:
        raise argparse.ArgumentTypeError(f"无法识别的大小: {text!r}（示例: 10M / 1G）")
    return int(float(m.group(1)) * _UNITS[m.group(2).lower()])


def human_size(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1000:
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1000
    return f"{n:.2f} TB"


def size_name(n: int) -> str:
    """把字节数转成文件名用的紧凑写法，如 10M / 100M / 1G。"""
    if n >= 10**9:
        return f"{n / 10**9:g}G"
    if n >= 10**6:
        return f"{n / 10**6:g}M"
    return f"{n / 10**3:g}K"


class Clock:
    """轻量毫秒时钟；缓存同一秒内重复的日期格式化结果，避免逐行算 datetime。"""

    def __init__(self, start: datetime):
        self._start = start
        self._off = 0            # 距 start 的毫秒偏移
        self._sec = -1
        self._frac = 0
        self._d2 = self._d4 = self._hms = self._sys = ""

    def advance(self, ms: int) -> None:
        self._off += ms

    def _refresh(self) -> None:
        sec, frac = divmod(self._off, 1000)
        if sec != self._sec:
            dt = self._start + timedelta(seconds=sec)
            self._sec = sec
            self._d2 = f"{dt.month:02d}-{dt.day:02d}"
            self._d4 = f"{dt.year:04d}-{dt.month:02d}-{dt.day:02d}"
            self._hms = f"{dt.hour:02d}:{dt.minute:02d}:{dt.second:02d}"
            self._sys = f"{MONTHS[dt.month]} {dt.day:2d} {self._hms}"
        self._frac = frac

    def stamp_short(self) -> str:    # 09-02 14:23:01.123
        self._refresh()
        return f"{self._d2} {self._hms}.{self._frac:03d}"

    def stamp_long(self) -> str:     # 2026-09-02 14:23:01.123
        self._refresh()
        return f"{self._d4} {self._hms}.{self._frac:03d}"

    def stamp_iso(self) -> str:      # 2026-09-02T14:23:01.123+08:00
        self._refresh()
        return f"{self._d4}T{self._hms}.{self._frac:03d}+08:00"

    def stamp_syslog(self) -> str:   # Sep  2 14:23:01
        self._refresh()
        return self._sys


# ---------------------------------------------------------------------------
# 内容素材池（全部为 GBK 安全字符，便于生成编码测试样本）
# ---------------------------------------------------------------------------

WORDS = ["北京", "上海", "广州", "天气预报", "输入法", "候选词", "日程提醒", "顺丰快递",
         "账户余额", "地铁十号线", "双十一优惠", "蓝牙耳机", "移动支付", "二维码",
         "视频会议", "人工智能", "机器学习"]
PHRASES = ["明天下午三点提醒我开会", "帮我查一下去上海的高铁票", "今天天气怎么样",
           "给张三发条消息说我马上到", "打开手电筒", "把亮度调到百分之五十",
           "附近有什么好吃的川菜馆", "把这个句子翻译成英文"]
URLS = ["/api/v1/sync", "/api/v1/user/profile", "/api/v1/history",
        "/api/v1/lexicon/delta", "/api/v1/config", "/api/v1/voice/asr",
        "/v2/search?suggest=true", "/metrics/report"]
PATHS = ["/data/data/com.sogou.input/cache/lexicon.dat",
         "/data/data/com.sogou.input/databases/history.db",
         "/sdcard/Android/data/com.sogou.input/files/backup.zip",
         "/var/log/sogou/sync-gateway.log",
         "/tmp/upload_34f2.bin"]
TABLES = ["history", "user_dict", "skin_meta", "freq_stat", "cloud_backup"]
OPS = ["loadLexicon", "renderKeyboard", "syncCloud", "parseResponse",
       "queryHistory", "decodeSkin", "compressUpload"]

APP_TAGS = ["InputMethodService", "KeyboardView", "CandidateBar", "LexiconManager",
            "NetworkMonitor", "OkHttp", "CloudSync", "CrashHandler", "SkinEngine",
            "VoiceSession", "SettingsStore", "HistoryProvider", "BatteryJob",
            "SogouImeService", "Choreographer", "ViewRootImpl", "SQLiteOpenHelper",
            "PoolThread-3", "DefaultDispatcher-2", "ActivityTaskManager"]

THREADS = [f"http-nio-8080-exec-{i}" for i in range(1, 13)] + [
    "scheduling-1", "pool-2-thread-3", "pool-2-thread-7", "main",
    "DefaultDispatcher-worker-2", "DefaultDispatcher-worker-5"]

SERVER_LOGGERS = [
    "com.sogou.server.gateway.ApiGateway", "com.sogou.server.sync.SyncService",
    "com.sogou.server.lic.LexiconCenter", "com.sogou.ime.core.CandidateEngine",
    "com.sogou.ime.cloud.OssClient", "com.sogou.common.db.HikariPool",
    "com.sogou.common.cache.RedisClient", "com.sogou.monitor.MetricsExporter",
    "com.sogou.push.PushDispatcher"]

SYS_LINES = [
    ("I", "chatty", "uid=10085(com.sogou.input) identical 24 lines"),
    ("I", "chatty", "uid=10085(com.sogou.input) expire 37 lines"),
    ("E", "lowmemorykiller", "Kill 'com.sogou.input (10085)' to free 230MB"),
    ("W", "libc", 'Access denied finding property "vendor.debug.tuner.is_4k"'),
    ("W", "wifi", "Failed to get RSSI"),
    ("W", "netd", "Unknown interface wlan1"),
    ("E", "BufferQueue", "dequeueBuffer: can't dequeue multiple buffers without setting the buffer count"),
    ("I", "gralloc", "Registering a buffer in the process that still has it"),
]
SYS_PIDS = [89, 152, 460, 733, 1520, 781]

SYSLOG_SERVICES = [
    ("mDNSResponder", "DNSServiceQueryRecord returned kDNSServiceErr_NoSuchRecord for sogou.local"),
    ("configd", "network configuration changed."),
    ("kernel", "audio output over-subscribed (0 60f77bb0)"),
    ("sshd", "Accepted publickey for builder from 10.20.30.40 port 51234 ssh2"),
    ("com.apple.xpc.launchd", "Service exited with abnormal code: 78"),
    ("fmd", "APFS snapshot created successfully"),
]

JSON_SERVICES = ["sync-gateway", "lexicon-center", "asr-service",
                 "push-center", "metrics-agent", "account-service"]
JSON_MSGS = ["sync batch completed", "lexicon delta published", "asr result committed",
             "push message delivered", "metrics snapshot flushed", "cache warmed up",
             "request routed to upstream", "用户请求处理完成", "词库打包任务结束", "降级开关已更新"]

EXCEPTIONS = [
    ("NullPointerException", "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference"),
    ("IllegalStateException", "Fragment already added: SettingsFragment{d2f1a3}"),
    ("ArrayIndexOutOfBoundsException", "length=16; index=-1"),
    ("NumberFormatException", 'For input string: "12a3f"'),
    ("OutOfMemoryError", "Failed to allocate a 10485760 byte allocation with 4194304 free bytes and 8MB until OOM"),
    ("SQLiteException", "no such table: freq_stat (code 1 SQLITE_ERROR)"),
]

STACK_FRAMES = [
    "at com.sogou.input.core.LexiconManager.load(LexiconManager.java:{n})",
    "at com.sogou.input.core.CandidateEngine.refresh(CandidateEngine.java:{n})",
    "at com.sogou.input.sync.SyncEngine$Companion.run(SyncEngine.kt:{n})",
    "at com.sogou.input.ui.SettingsActivity.onCreate(SettingsActivity.java:{n})",
    "at com.sogou.input.skin.SkinLoader.decodeRegion(SkinLoader.java:{n})",
    "at com.sogou.input.common.db.HistoryDao.query(HistoryDao.kt:{n})",
    "at com.sogou.input.cloud.OssClient.upload(OssClient.java:{n})",
    "at android.app.Activity.performCreate(Activity.java:7802)",
    "at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1329)",
    "at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3148)",
    "at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:3295)",
    "at android.os.Handler.handleCallback(Handler.java:883)",
    "at android.os.Handler.dispatchMessage(Handler.java:99)",
    "at android.os.Looper.loop(Looper.java:233)",
    "at java.lang.Thread.run(Thread.java:919)",
    "at kotlinx.coroutines.Dispatchers$Task.run(DispatchersTask.kt:{n})",
]

ANR_ACTIVITIES = [
    "com.sogou.input/.ui.SettingsActivity",
    "com.sogou.input/.skin.SkinStoreActivity",
    "com.sogou.input/.voice.VoiceInputActivity",
    "com.sogou.input/.cloud.CloudBackupActivity",
]

TEMPLATES: dict[str, list[str]] = {
    "D": [
        "onCreate elapsed={ms}ms, inflated {n} views",
        "touchEvent action={action} x={x} y={y}",
        "candidate update, size={n}, commitText='{word}'",
        "dumpsys meminfo resident={mb}MB",
        "lazy load lexicon part={n}/{n2}, cost={ms}ms",
        "音效资源预加载完成, 共 {n} 个",
        "HTTP {verb} {url} -> {code} ({ms}ms)",
        "sql: SELECT * FROM {table} WHERE uid={uid} LIMIT {n}",
        "keyboard switch to mode={mode}, reason=user",
        "skin region cache hit, key={key}",
        "frame stats: avg={ms}ms, jank={n}",
        "decoder buffer consumed={n}KB",
    ],
    "I": [
        "Network connected, type=WIFI, ssid={ssid}",
        "Request started {verb} {url}",
        "词库增量同步完成, 新增 {n} 词条",
        "User login succeeded, uid={uid}",
        "Config refreshed from remote, version={ver}",
        "cloud backup finished, objects={n}, size={mb}MB",
        "语音识别结果: '{words}' (confidence=0.{n2})",
        "clipboard monitor started",
        "app upgrade to {ver} applied",
        "夜间流量任务已排队, 窗口 02:00-05:00",
        "每日热词更新: {n} 条",
        "字体缓存重建完成, families={n}",
        "电量变化: {n}% (discharging)",
        "sync heartbeat ok, rtt={ms}ms",
    ],
    "W": [
        "Memory usage high: {mb}MB (threshold=512MB)",
        "Retry request after timeout, attempt={n}, url={url}",
        "Slow operation detected: {op} took {ms}ms",
        "缓存命中率偏低: {n}%",
        "Battery low ({n}%), background sync deferred",
        "Skipped {n} frames! The application may be doing too much work on its main thread",
        "词库文件校验失败, 尝试恢复备份: {path}",
        "disk space below threshold, remaining={n}MB",
        "ANR risk: main thread blocked {ms}ms on {op}",
        "certificate expires in {n} days",
        "duplicate push received, msgId={n}",
    ],
    "E": [
        "Connection timeout after {ms}ms, url={url}",
        "Failed to parse response JSON: Unexpected token at position {n}",
        "File not found: {path}",
        "数据库查询失败: table={table}, errno={n}",
        "HTTP {code} from upstream {url}",
        "upload to OSS failed, key={key}, code={code}",
        "Decode skin package failed: {path}",
        "语音识别请求失败: ASR 服务返回 {code}",
        "write log failed, disk may be full, errno={n}",
        "uncaught exception in worker: {exc}",
    ],
}

# 占位符 -> 取值函数（r 为本生成器的 Random 实例）
VALS = {
    "ms":     lambda r: str(r.randint(8, 4500)),
    "mb":     lambda r: str(r.randint(90, 980)),
    "n":      lambda r: str(r.randint(2, 9999)),
    "n2":     lambda r: str(r.randint(2, 64)),
    "uid":    lambda r: str(r.randint(10000, 999999)),
    "word":   lambda r: r.choice(WORDS),
    "words":  lambda r: r.choice(PHRASES),
    "url":    lambda r: r.choice(URLS),
    "path":   lambda r: r.choice(PATHS),
    "table":  lambda r: r.choice(TABLES),
    "code":   lambda r: r.choice(["500", "502", "504", "408", "429"]),
    "op":     lambda r: r.choice(OPS),
    "mode":   lambda r: r.choice(["26键", "9键", "手写", "语音", "拼音"]),
    "action": lambda r: r.choice(["DOWN", "MOVE", "UP", "CANCEL"]),
    "x":      lambda r: str(r.randint(0, 1080)),
    "y":      lambda r: str(r.randint(0, 2400)),
    "verb":   lambda r: r.choice(["GET", "POST", "PUT", "DELETE"]),
    "key":    lambda r: f"lexicon/{r.randint(1000, 9999)}/part-{r.randint(1, 64)}.dat",
    "ssid":   lambda r: r.choice(["Office-5G", "Home-WiFi", "CMCC-Free", "sogou-guest"]),
    "ver":    lambda r: f"{r.randint(8, 12)}.{r.randint(0, 9)}.{r.randint(0, 99)}",
    "exc":    lambda r: r.choice([e for e, _ in EXCEPTIONS]),
}

_HEADER = (
    "--------- beginning of /dev/log/system\n"
    "--------- beginning of /dev/log/main\n"
)
_BATCH_LINES = 4096        # 每批生成的行数
_REPORT_STEP = 100 * 10**6  # 进度打印间隔（字节）
_B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="


class LogGenerator:
    """单文件的日志内容生成器（保持自身时间轴 / traceId / 统计状态）。"""

    def __init__(self, seed: int):
        self.rnd = random.Random(seed)
        self.clock = Clock(datetime(2026, 9, 2, 8, 13, 42))
        self.pid = self.rnd.randint(3000, 9999)
        self.tids = [self.pid] + [self.pid + self.rnd.randint(1, 600) for _ in range(24)]
        self.trace = self._new_trace_id()
        self.trace_left = 0
        self.line_no = 0
        self.stats = {"total": 0, "D": 0, "I": 0, "W": 0, "E": 0,
                      "fatal": 0, "anr": 0, "json": 0, "long": 0, "gc": 0}
        # 模板 -> 具体消息池（每个模板 8 个变体，避免逐行做占位符填充）
        self.msgs = {lvl: [self._fill(t) for t in tpl for _ in range(8)]
                     for lvl, tpl in TEMPLATES.items()}
        self.frames = [self._fill(t) for t in STACK_FRAMES]

    # -- 内部工具 ----------------------------------------------------------

    def _fill(self, template: str) -> str:
        out = []
        for literal, field, spec, _conv in Formatter().parse(template):
            out.append(literal)
            if field:
                val = VALS[field](self.rnd)
                out.append(format(val, spec) if spec else val)
        return "".join(out)

    def _new_trace_id(self) -> str:
        return "tr-%08x" % self.rnd.getrandbits(32)

    def _trace_id(self) -> str:
        """同一个 traceId 连续出现若干行，方便测试 F9-3 关联定位。"""
        if self.trace_left <= 0:
            self.trace = self._new_trace_id()
            self.trace_left = self.rnd.randint(3, 30)
        self.trace_left -= 1
        return self.trace

    def _pick_level(self) -> str:
        r = self.rnd.random()
        if r < 0.55:
            return "I"
        if r < 0.72:
            return "D"
        if r < 0.88:
            return "W"
        return "E"

    # -- 各类日志行 --------------------------------------------------------

    def android_line(self, level: str, tag: str | None = None, msg: str | None = None) -> str:
        tid = self.rnd.choice(self.tids)
        tag = tag or self.rnd.choice(APP_TAGS)
        if msg is None:
            msg = self.rnd.choice(self.msgs[level])
        return f"{self.clock.stamp_short()} {self.pid:>5} {tid:>5} {level} {tag}: {msg}"

    def system_line(self) -> str:
        level, tag, msg = self.rnd.choice(SYS_LINES)
        pid = self.rnd.choice(SYS_PIDS)
        return f"{self.clock.stamp_short()} {pid:>5} {pid:>5} {level} {tag}: {msg}"

    def server_line(self, level: str) -> str:
        lvl_name = {"D": "DEBUG", "I": "INFO", "W": "WARN", "E": "ERROR"}[level]
        thread = self.rnd.choice(THREADS)
        logger = self.rnd.choice(SERVER_LOGGERS)
        msg = self.rnd.choice(self.msgs[level])
        return (f"{self.clock.stamp_long()} [{lvl_name}] [{thread}] {logger} - {msg} "
                f"(traceId={self._trace_id()}, span={self.rnd.randint(1, 99)}ms)")

    def json_line(self) -> str:
        r = self.rnd.random()
        level = "ERROR" if r < 0.10 else ("WARN" if r < 0.25 else "INFO")
        return ('{"ts":"%s","level":"%s","service":"%s","traceId":"%s","msg":"%s",'
                '"count":%d,"latencyMs":%d,"success":%s}'
                % (self.clock.stamp_iso(), level, self.rnd.choice(JSON_SERVICES),
                   self._trace_id(), self.rnd.choice(JSON_MSGS),
                   self.rnd.randint(0, 99999), self.rnd.randint(1, 9500),
                   "true" if level == "INFO" else str(r < 0.6).lower()))

    def syslog_line(self) -> str:
        svc, msg = self.rnd.choice(SYSLOG_SERVICES)
        pid = self.rnd.randint(50, 3000)
        return f"{self.clock.stamp_syslog()} sogou-mbp {svc}[{pid}]: {msg}"

    def gc_line(self) -> str:
        self.stats["gc"] += 1
        self.clock.advance(self.rnd.randint(1, 5))
        freed = self.rnd.randint(20000, 200000)
        return (f"{self.clock.stamp_short()} {self.pid:>5} {self.pid:>5} I art     : "
                f"Background partial concurrent mark sweep GC freed {freed}({self.rnd.randint(1, 12)}MB) "
                f"AllocSpace objects, {self.rnd.randint(2, 120)}({self.rnd.randint(1, 8)}MB) LOS objects, "
                f"{self.rnd.randint(25, 45)}% free, {self.rnd.randint(80, 160)}MB/{self.rnd.randint(160, 260)}MB, "
                f"paused {self.rnd.randint(1, 40)}.{self.rnd.randint(0, 999):03d}ms "
                f"total {self.rnd.randint(50, 400)}.{self.rnd.randint(0, 999):03d}ms")

    def long_line(self) -> str:
        """2~8KB 的超长行，测试 F2-4 长文本原则（UI 截断但原始数据完整）。"""
        self.stats["long"] += 1
        self.clock.advance(self.rnd.randint(2, 20))
        payload = "".join(self.rnd.choices(_B64, k=self.rnd.randint(2, 8) * 1024))
        tid = self.rnd.choice(self.tids)
        return (f"{self.clock.stamp_short()} {self.pid:>5} {tid:>5} D CloudSync: "
                f"payload dump(backup.zip, part={self.rnd.randint(1, 64)}): {payload}")

    def fatal_block(self) -> list[str]:
        """FATAL EXCEPTION 块（含堆栈），测试 2.2 场景的 Crash 过滤器。"""
        self.stats["fatal"] += 1
        pid = self.pid
        exc, detail = self.rnd.choice(EXCEPTIONS)
        lines: list[str] = []

        def emit(text: str) -> None:
            self.clock.advance(self.rnd.randint(0, 2))
            lines.append(f"{self.clock.stamp_short()} {pid:>5} {pid:>5} E AndroidRuntime: {text}")

        emit("FATAL EXCEPTION: main")
        emit(f"Process: com.sogou.input, PID: {pid}")
        emit(f"java.lang.{exc}: {detail}")
        for frame in self.rnd.sample(self.frames, k=self.rnd.randint(9, 15)):
            emit("\\tat " + frame)
        return lines

    def anr_block(self) -> list[str]:
        """ANR 块（ActivityManager），测试 2.2 场景的 ANR 过滤器。"""
        self.stats["anr"] += 1
        pid = self.pid
        lines: list[str] = []

        def emit(text: str) -> None:
            self.clock.advance(self.rnd.randint(0, 2))
            lines.append(f"{self.clock.stamp_short()}  1520  1540 E ActivityManager: {text}")

        emit(f"ANR in com.sogou.input ({self.rnd.choice(ANR_ACTIVITIES)})")
        emit(f"PID: {pid}")
        emit("Reason: Input dispatching timed out (Waiting because the touched window "
             "has not finished processing the input events that were previously delivered to it.)")
        t = self.clock.stamp_short()
        emit(f"CPU usage from {t} to {t}+5min:")
        emit(f"  {self.rnd.randint(20, 60)}% {pid}/com.sogou.input: "
             f"{self.rnd.randint(5, 30)}% user + {self.rnd.randint(5, 30)}% kernel / "
             f"faults: {self.rnd.randint(5000, 90000)} minor {self.rnd.randint(10, 200)} major")
        emit(f"  {self.rnd.randint(5, 20)}% 1520/system_server: "
             f"{self.rnd.randint(2, 10)}% user + {self.rnd.randint(2, 10)}% kernel / "
             f"faults: {self.rnd.randint(20000, 60000)} minor")
        emit(f"  {self.rnd.randint(60, 99)}% TOTAL: "
             f"{self.rnd.randint(40, 70)}% user + {self.rnd.randint(20, 40)}% kernel")
        return lines

    # -- 批量生成 ----------------------------------------------------------

    def batch(self, n: int = _BATCH_LINES) -> str:
        out: list[str] = []
        rnd = self.rnd
        for _ in range(n):
            self.line_no += 1
            no = self.line_no
            # 稀疏特殊事件：按行号节奏触发，保证每个文件里都有足够的过滤/跳转目标
            # （节奏设置保证 10M 文件约 8.6 万行内也能包含 FATAL 与 ANR 块）
            if no % 30000 == 0:
                out.extend(self.fatal_block())
                continue
            if no % 75000 == 0:
                out.extend(self.anr_block())
                continue
            if no % 15000 == 0:
                out.append(self.gc_line())
                continue
            if no % 4000 == 0:
                out.append(self.long_line())
                continue
            self.clock.advance(rnd.randint(4, 90))
            r = rnd.random()
            if r < 0.52:                      # Android 应用日志
                lvl = self._pick_level()
                out.append(self.android_line(lvl))
                self.stats[lvl] += 1
            elif r < 0.60:                    # Android 系统进程日志
                out.append(self.system_line())
            elif r < 0.88:                    # 服务端日志
                lvl = self._pick_level()
                out.append(self.server_line(lvl))
                self.stats[lvl] += 1
            elif r < 0.93:                    # 单行 JSON
                out.append(self.json_line())
                self.stats["json"] += 1
            else:                             # syslog（未知格式）
                out.append(self.syslog_line())
        self.stats["total"] += len(out)
        return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------
# 文件生成
# ---------------------------------------------------------------------------

def generate_file(path: Path, target: int, gen: LogGenerator, quiet: bool) -> tuple[int, int, float]:
    """写入文件直到达到目标字节数，返回 (实际字节, 实际行数, 耗时秒)。"""
    t0 = time.monotonic()
    written = 0
    lines = 0
    report_at = _REPORT_STEP
    with open(path, "wb") as f:
        header = _HEADER.encode("utf-8")
        f.write(header)
        written += len(header)
        lines += header.count(b"\n")
        while written < target:
            data = gen.batch().encode("utf-8")
            f.write(data)
            written += len(data)
            lines += data.count(b"\n")
            if not quiet and written >= report_at:
                report_at += _REPORT_STEP
                pct = min(written / target, 1.0) * 100
                print(f"    {human_size(written)} / {human_size(target)} "
                      f"({pct:.0f}%)  {time.monotonic() - t0:.1f}s", flush=True)
    return written, lines, time.monotonic() - t0


def make_encoding_samples(out_dir: Path, gen: LogGenerator) -> None:
    """额外生成约 1MB 的 GBK / UTF-16LE 编码样本，用于 F1-3 编码识别测试。"""
    target = 10**6
    chunks: list[str] = []
    got = 0
    while got < target:
        c = gen.batch(2048)
        chunks.append(c)
        got += len(c.encode("utf-8"))
    text = "".join(chunks)
    utf8 = text.encode("utf-8")
    if len(utf8) > target:  # 截断到行边界
        utf8 = utf8[:target].rsplit(b"\n", 1)[0] + b"\n"
        text = utf8.decode("utf-8")
    (out_dir / "sample_1M_gbk.log").write_bytes(text.encode("gbk", errors="replace"))
    (out_dir / "sample_1M_utf16le.log").write_bytes(b"\xff\xfe" + text.encode("utf-16-le"))
    print("    编码样本: sample_1M_gbk.log, sample_1M_utf16le.log (各约 1MB)")


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="LogViewer 测试日志生成器（大文件性能测试用）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n"
               "  python3 scripts/generate_test_logs.py\n"
               "  python3 scripts/generate_test_logs.py --sizes 5G\n"
               "  python3 scripts/generate_test_logs.py --sizes 10M,100M --with-encoding-samples")
    ap.add_argument("--sizes", default="10M,100M,500M,1G",
                    help="逗号分隔的目标大小（十进制），默认 10M,100M,500M,1G")
    ap.add_argument("--out-dir", default=None,
                    help="输出目录（默认: 项目根目录下 testLogs/）")
    ap.add_argument("--seed", type=int, default=20260902,
                    help="随机种子（默认 20260902，保证可复现）")
    ap.add_argument("--with-encoding-samples", action="store_true",
                    help="额外生成 1MB 的 GBK / UTF-16LE 编码样本（F1-3 测试用）")
    ap.add_argument("--quiet", action="store_true", help="不输出进度信息")
    args = ap.parse_args(argv)

    try:
        sizes = [parse_size(s) for s in args.sizes.split(",") if s.strip()]
    except argparse.ArgumentTypeError as e:
        ap.error(str(e))
        return 2
    if not sizes:
        ap.error("--sizes 不能为空")
        return 2

    out_dir = (Path(args.out_dir).resolve() if args.out_dir
               else Path(__file__).resolve().parent.parent / "testLogs")
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"输出目录: {out_dir}")
    t_all = time.monotonic()
    for i, size in enumerate(sizes):
        name = f"test_{size_name(size)}.log"
        gen = LogGenerator(args.seed + i)  # 每个文件不同种子，内容互不相同
        print(f"[{i + 1}/{len(sizes)}] 生成 {name}（目标 {human_size(size)}）...")
        written, lines, secs = generate_file(out_dir / name, size, gen, args.quiet)
        s = gen.stats
        print(f"    完成: {human_size(written)}, {lines:,} 行, 用时 {secs:.1f}s")
        print(f"    内容: INFO {s['I']:,} / DEBUG {s['D']:,} / WARN {s['W']:,} / "
              f"ERROR {s['E']:,} | FATAL 块 {s['fatal']:,} | ANR 块 {s['anr']:,} | "
              f"JSON {s['json']:,} | 超长行 {s['long']:,} | GC {s['gc']:,}")

    if args.with_encoding_samples:
        print("生成编码测试样本...")
        make_encoding_samples(out_dir, LogGenerator(args.seed + 9000))

    print(f"全部完成，总用时 {time.monotonic() - t_all:.1f}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
