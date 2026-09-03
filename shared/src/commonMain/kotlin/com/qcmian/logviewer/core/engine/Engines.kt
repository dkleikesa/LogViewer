package com.qcmian.logviewer.core.engine

import com.qcmian.logviewer.core.doc.DocumentSource
import com.qcmian.logviewer.core.doc.InMemoryDocument
import com.qcmian.logviewer.core.doc.asciiLowercase
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.MatchMode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 基准/调试跟踪开关（生产恒 false） */
object BenchTrace {
    var enabled = false
}

/* ==================== 过滤管道（3.3） ==================== */

/**
 * 处理管道：包含 → 排除 → 当前视图
 * - 有任一启用 Include 时：命中任一 Include 条件的行进入候选集；
 *   无启用 Include 时，全部行进入候选集（D7）
 * - Exclude 在候选集上执行，命中的行被隐藏
 * - 着色优先级：按列表顺序第一个命中的过滤器（F3-5）
 */
class LinePipeline(filters: List<FilterConfig>) {

    data class Entry(
        val config: FilterConfig,
        val matcher: Matcher?,
    ) {
        val isInclude get() = config.type == FilterType.INCLUDE
    }

    val entries: List<Entry> = filters.filter { it.enabled }.map { Entry(it, buildMatcher(it)) }
    val hasInclude: Boolean = entries.any { it.isInclude }
    val hasInvalid: Boolean = entries.any { it.matcher == null }

    /** 可复用的行评估结果（避免逐行分配；单协程使用，由调用方持有实例） */
    class LineEval {
        var pass: Boolean = false
        var colorEntry: Entry? = null
    }

    /** 评估一行，结果写入 [out]（零分配）并返回 */
    fun eval(text: String, markers: Set<Int>?, out: LineEval): LineEval {
        var candidate = !hasInclude
        var color: Entry? = null
        for (e in entries) {
            val m = e.matcher ?: continue
            if (m.matches(text, markers)) {
                if (color == null) color = e
                if (e.isInclude) candidate = true
            }
        }
        if (candidate) {
            for (e in entries) {
                if (e.isInclude) continue
                val m = e.matcher ?: continue
                if (m.matches(text, markers)) {
                    candidate = false
                    break
                }
            }
        }
        out.pass = candidate
        out.colorEntry = if (candidate) color else null
        return out
    }

    companion object {
        fun buildMatcher(f: FilterConfig): Matcher? = when (f.matchMode) {
            MatchMode.STRING ->
                if (f.pattern.isEmpty()) null
                else StringMatcher(f.pattern, f.caseSensitive)
            MatchMode.REGEX ->
                try {
                    RegexMatcher(
                        if (f.pattern.startsWith("^") || f.pattern.endsWith("$")) f.pattern
                        else f.pattern,
                        f.caseSensitive,
                    )
                } catch (_: Exception) {
                    null
                }
            MatchMode.MARKER -> MarkerMatcher(f.markerIndex)
        }
    }
}

interface Matcher {
    fun matches(text: String, markers: Set<Int>?): Boolean
}

class StringMatcher(private val needle: String, private val caseSensitive: Boolean) : Matcher {
    private val lowerNeedle = if (caseSensitive) needle else needle.lowercase()

    override fun matches(text: String, markers: Set<Int>?): Boolean =
        if (caseSensitive) text.contains(needle)
        else containsIgnoreCase(text, lowerNeedle)

    /** 大小写不敏感包含：regionMatches 扫描，不为每行分配 lowercase 拷贝（百万行场景避免 GB 级临时分配） */
    private fun containsIgnoreCase(hay: String, lowerNeedle: String): Boolean {
        val n = lowerNeedle.length
        if (n == 0) return true
        val last = hay.length - n
        var i = 0
        while (i <= last) {
            if (hay.regionMatches(i, lowerNeedle, 0, n, ignoreCase = true)) return true
            i++
        }
        return false
    }
}

class RegexMatcher(pattern: String, caseSensitive: Boolean) : Matcher {
    internal val regex = Regex(
        pattern,
        if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
    )

    override fun matches(text: String, markers: Set<Int>?): Boolean = regex.containsMatchIn(text)

    companion object {
        fun compile(pattern: String, caseSensitive: Boolean): RegexMatcher? = try {
            RegexMatcher(pattern, caseSensitive)
        } catch (_: Exception) {
            null
        }

        fun rangesOf(pattern: String, caseSensitive: Boolean, text: String): List<IntRange> {
            val m = compile(pattern, caseSensitive) ?: return emptyList()
            return m.regex.findAll(text).map { it.range }.toList()
        }
    }
}

class MarkerMatcher(private val markerIndex: Int) : Matcher {
    override fun matches(text: String, markers: Set<Int>?): Boolean =
        markers?.contains(markerIndex) == true
}

/* ==================== 过滤结果 ==================== */

class FilterResult(
    /** 通过管道的行索引（有序） */
    val matched: IntArray,
    /** 命中行 -> 第一个命中过滤器索引（对应应用时的 filters 列表），仅 Highlight/着色需要 */
    val colors: HashMap<Int, Int>?,
    /** 每个过滤器（含未启用，按原始列表下标）的命中行数 */
    val counts: IntArray,
    /** 结果生成时的过滤器指纹（列表内容 hash），用于判断是否需要重算 */
    val fingerprint: Long,
) {
    companion object {
        val Empty = FilterResult(IntArray(0), null, IntArray(0), 0L)
    }
}

fun filterFingerprint(filters: List<FilterConfig>): Long {
    var h = 1125899906842597L
    for (f in filters) {
        h = 31 * h + f.hashCode()
    }
    return h
}

/**
 * 执行过滤（后台、可取消）。
 * 快路径：全部启用规则为「文本 + 区分大小写」时走全文 indexOf 扫描（向量化 intrinsic）；
 * 其余（正则/标记/忽略大小写）回退逐行。
 * @param markerProvider 行索引 -> 该行标记集合（无 MARKER 过滤器时可传 null）
 */
suspend fun runFilter(
    doc: DocumentSource,
    filters: List<FilterConfig>,
    fingerprint: Long,
    markerProvider: (suspend (Int) -> Set<Int>?)?,
    onProgress: (Float) -> Unit,
): FilterResult {
    val active = filters.filter { it.enabled }
    if (active.isEmpty()) {
        onProgress(1f)
        return FilterResult(IntArray(0), null, IntArray(filters.size), fingerprint)
    }
    // 快路径：紧凑文档 + 文本/正则规则（正则全文一次 findAll + 命中偏移二分行号；
    // 正则编译失败或 MARKER 规则回退逐行）
    val regexCache = HashMap<Long, RegexMatcher?>()
    var fastEligible = doc is InMemoryDocument &&
        active.all { (it.matchMode == MatchMode.STRING || it.matchMode == MatchMode.REGEX) && it.pattern.isNotEmpty() }
    if (fastEligible && doc is InMemoryDocument) {
        for (f in active) {
            if (f.matchMode == MatchMode.REGEX) {
                val m = RegexMatcher.compile(f.pattern, f.caseSensitive)
                if (m == null) {
                    fastEligible = false
                    break
                }
                regexCache[f.id] = m
            }
        }
    }
    if (fastEligible) {
        return runFilterFastPath(doc as InMemoryDocument, filters, active, fingerprint, onProgress, regexCache)
    }
    val pipeline = LinePipeline(filters)
    val needMarkers = filters.any { it.enabled && it.matchMode == MatchMode.MARKER }
    val scratch = LinePipeline.LineEval()   // 全程复用，零逐行分配
    val total = doc.lineCount
    // 原始类型增长缓冲：避免 ArrayList<Int> 百万级装箱分配
    val matched = IntList(maxOf(64, total / 16))
    val colors = HashMap<Int, Int>()
    val counts = IntArray(filters.size)
    val idToIndex = HashMap<Long, Int>(filters.size)
    filters.forEachIndexed { i, f -> idToIndex[f.id] = i }

    val batchSize = 8192
    var i = 0
    var lastProgress = 0f
    while (i < total) {
        val end = minOf(total, i + batchSize)
        for (lineIdx in i until end) {
            val line = doc.line(lineIdx)
            val markers = if (needMarkers) markerProvider?.invoke(lineIdx) else null
            val ev = pipeline.eval(line, markers, scratch)
            if (ev.pass) matched.add(lineIdx)
            val colorEntry = ev.colorEntry
            if (colorEntry != null) {
                val fi = idToIndex[colorEntry.config.id] ?: 0
                counts[fi]++
                // 只有配置了着色的过滤器才写行色映射（避免百万级无色命中撑爆 HashMap）
                if (colorEntry.config.background != null || colorEntry.config.foreground != null) {
                    colors[lineIdx] = fi
                }
            }
        }
        // 协作取消：被新过滤请求取代时尽早终止，不与新任务抢 CPU
        currentCoroutineContext().ensureActive()
        // 进度节流：每 2% 或完成时才回调，避免每批状态写触发上千次 UI 重组
        val p = end.toFloat() / total
        if (p - lastProgress >= 0.02f || end >= total) {
            onProgress(p)
            lastProgress = p
        }
        i = end
    }
    return FilterResult(matched.toArray(), colors, counts, fingerprint)
}

/** 原始类型 Int 增长缓冲（免装箱） */
private class IntList(initialCapacity: Int) {
    private var arr = IntArray(initialCapacity.coerceAtLeast(16))
    private var n = 0

    fun add(v: Int) {
        if (n == arr.size) arr = arr.copyOf(arr.size * 2)
        arr[n++] = v
    }

    fun toArray(): IntArray = if (n == arr.size) arr else arr.copyOf(n)
}

/** 快路径：每规则一次全文 indexOf 扫描（向量化）+ 命中位置二分行号 + 位图组合 */
private suspend fun runFilterFastPath(
    doc: InMemoryDocument,
    filters: List<FilterConfig>,
    active: List<FilterConfig>,
    fingerprint: Long,
    onProgress: (Float) -> Unit,
    regexCache: Map<Long, RegexMatcher?>,
): FilterResult {
    val text = doc.text
    val totalLines = doc.lineCount
    val counts = IntArray(filters.size)
    val idToIndex = HashMap<Long, Int>(filters.size)
    filters.forEachIndexed { i, f -> idToIndex[f.id] = i }

    val t0 = com.qcmian.logviewer.platform.nowMillis()
    // 存在忽略大小写的文本规则时构建（并缓存）小写全文——与原文等长，行偏移直接复用
    // 正则规则的大小写由 regex flags 处理，不依赖小写全文
    // 进度：构建占 8%→55%（每完成一个并行段推进），等锁（预热协程先建）时跳到 55%
    val anyIgnoreCase = active.any { it.matchMode == MatchMode.STRING && !it.caseSensitive }
    var lowerText: String? = null
    if (anyIgnoreCase) {
        onProgress(0.08f)
        lowerText = doc.lowerText { segP -> onProgress(0.08f + 0.47f * segP) }
    }
    val t1 = com.qcmian.logviewer.platform.nowMillis()

    // 每规则：全文命中行（有序去重 IntArray）；hits 扫描占 55%→90%
    val includeRules = ArrayList<Pair<FilterConfig, IntArray>>(4)   // (config, hits)
    val excludeRules = ArrayList<Pair<FilterConfig, IntArray>>(4)
    active.forEachIndexed { ai, f ->
        currentCoroutineContext().ensureActive()
        val fi = idToIndex[f.id] ?: 0
        val hits = when (f.matchMode) {
            MatchMode.REGEX -> collectRegexHitLines(text, doc, regexCache[f.id]!!)
            else -> {
                val hay = if (f.caseSensitive) text else lowerText!!
                val needle = if (f.caseSensitive) f.pattern else asciiLowercase(f.pattern)
                collectHitLines(hay, doc, needle)
            }
        }
        counts[fi] = hits.size
        (if (f.type == FilterType.INCLUDE) includeRules else excludeRules).add(f to hits)
        onProgress(0.55f + 0.35f * (ai + 1) / active.size)
    }
    val t2 = com.qcmian.logviewer.platform.nowMillis()
    if (BenchTrace.enabled) {
        println("[fast] lowerText=${t1 - t0}ms hits=${t2 - t1}ms lines=${doc.lineCount}")
    }

    // 位图组合：candidate = include 命中并集（无 include 则全行）；matched = candidate - exclude 命中
    val words = (totalLines + 63) / 64
    val inc = LongArray(words)
    val hasInclude = includeRules.isNotEmpty()
    if (hasInclude) {
        for ((_, hits) in includeRules) for (line in hits) setBit(inc, line)
    }
    val exc = LongArray(words)
    for ((_, hits) in excludeRules) for (line in hits) setBit(exc, line)

    // 有色规则才需要 colors
    val colorRules = includeRules.filter { it.first.background != null || it.first.foreground != null }
    val colors = if (colorRules.isEmpty()) null else HashMap<Int, Int>()

    val matched = IntList(maxOf(64, totalLines / 8))
    for (line in 0 until totalLines) {
        val candidate = !hasInclude || getBit(inc, line)
        if (!candidate || getBit(exc, line)) continue
        matched.add(line)
        if (colors != null) {
            for ((cfg, hits) in colorRules) {
                if (containsSorted(hits, line)) {
                    colors[line] = idToIndex[cfg.id] ?: 0
                    break
                }
            }
        }
    }
    onProgress(1f)
    return FilterResult(matched.toArray(), colors, counts, fingerprint)
}

/** 全文命中 → 命中行号（有序去重；lineOfPos 单调非降） */
private fun collectHitLines(text: String, doc: InMemoryDocument, needle: String): IntArray {
    val out = IntList(256)
    var from = 0
    var last = -1
    while (true) {
        val idx = text.indexOf(needle, from)
        if (idx < 0) break
        val line = doc.lineOfPos(idx)
        if (line != last) {
            out.add(line)
            last = line
        }
        from = idx + needle.length
    }
    return out.toArray()
}

/** 正则全文一次扫描 → 命中行号（有序去重；省 847 万次逐行 matcher/行分配） */
private fun collectRegexHitLines(text: String, doc: InMemoryDocument, matcher: RegexMatcher): IntArray {
    val out = IntList(256)
    var last = -1
    for (m in matcher.regex.findAll(text)) {
        val line = doc.lineOfPos(m.range.first)
        if (line != last) {
            out.add(line)
            last = line
        }
    }
    return out.toArray()
}

private fun setBit(bits: LongArray, i: Int) {
    bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
}

private fun getBit(bits: LongArray, i: Int): Boolean =
    (bits[i ushr 6] and (1L shl (i and 63))) != 0L

private fun containsSorted(arr: IntArray, v: Int): Boolean {
    var lo = 0
    var hi = arr.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            arr[mid] == v -> return true
            arr[mid] < v -> lo = mid + 1
            else -> hi = mid - 1
        }
    }
    return false
}

class SearchSpec(
    val query: String,
    val regex: Boolean,
    val caseSensitive: Boolean,
    val wholeWord: Boolean = false,
) {
    private val stringMatcher: StringMatcher? =
        if (!regex && query.isNotEmpty()) StringMatcher(query, caseSensitive) else null
    private val regexMatcher: RegexMatcher? =
        if (regex && query.isNotEmpty()) {
            // 全词：在用户正则两侧包 \b…\b（用户已含 \b 则不重复包）
            val wrapped =
                if (wholeWord && query.isNotEmpty() && !query.startsWith("\\b") && !query.endsWith("\\b"))
                    "\\b$query\\b"
                else query
            RegexMatcher.compile(wrapped, caseSensitive)
        } else null

    val valid: Boolean get() = query.isNotEmpty() &&
        (!regex || regexMatcher != null)

    fun matches(text: String): Boolean = when {
        stringMatcher != null -> {
            if (!wholeWord) stringMatcher.matches(text, null)
            else wholeWordContains(text)
        }
        regexMatcher != null -> regexMatcher.matches(text, null)
        else -> false
    }

    /** 行内命中片段（高亮用） */
    fun rangesIn(text: String): List<IntRange> = when {
        stringMatcher != null -> {
            if (!wholeWord) findAllIndexOf(text)
            else wholeWordRanges(text)
        }
        regexMatcher != null -> regexMatcher!!.regex.findAll(text).map { it.range }.toList()
        else -> emptyList()
    }

    private fun findAllIndexOf(text: String): List<IntRange> {
        val out = ArrayList<IntRange>(4)
        if (query.isEmpty()) return out
        val hay = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) query else query.lowercase()
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            out.add(idx until (idx + needle.length))
            from = idx + needle.length
        }
        return out
    }

    private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    /** 全词匹配：检索 needle 必须被非单词字符（或文本边界）包围 */
    private fun wholeWordContains(text: String): Boolean {
        if (query.isEmpty()) return false
        val needle = if (caseSensitive) query else query.lowercase()
        val hay = if (caseSensitive) text else text.lowercase()
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !isWordChar(hay[idx - 1])
            val afterOk = idx + needle.length == hay.length || !isWordChar(hay[idx + needle.length])
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
    }

    private fun wholeWordRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>(4)
        if (query.isEmpty()) return out
        val needle = if (caseSensitive) query else query.lowercase()
        val hay = if (caseSensitive) text else text.lowercase()
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            val beforeOk = idx == 0 || !isWordChar(hay[idx - 1])
            val afterOk = idx + needle.length == hay.length || !isWordChar(hay[idx + needle.length])
            if (beforeOk && afterOk) out.add(idx until (idx + needle.length))
            from = idx + 1
        }
        return out
    }
}

/**
 * 执行搜索（后台、可取消）。
 * @param scope null = 全文件；否则在该行索引序列中搜索
 */
suspend fun runSearch(
    doc: DocumentSource,
    spec: SearchSpec,
    scope: IntArray?,
    onProgress: (Float) -> Unit,
): IntArray {
    if (!spec.valid) return IntArray(0)
    val total = scope?.size ?: doc.lineCount
    if (total == 0) return IntArray(0)
    val out = ArrayList<Int>(minOf(total, 4096))
    val batchSize = 4096
    var i = 0
    while (i < total) {
        coroutineContext.ensureActive()
        val end = minOf(total, i + batchSize)
        if (scope != null) {
            // 作用域行：逐行访问（连续段）
            var j = i
            while (j < end) {
                val segStart = j
                while (j + 1 < end && scope[j + 1] == scope[j] + 1) j++
                for (k in segStart..j) {
                    val idx = scope[k]
                    if (spec.matches(doc.line(idx))) out.add(idx)
                }
                j++
            }
        } else {
            for (idx in i until end) {
                if (spec.matches(doc.line(idx))) out.add(idx)
            }
        }
        onProgress(end.toFloat() / total)
        i = end
    }
    return out.toIntArray()
}
