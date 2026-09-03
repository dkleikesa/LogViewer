package com.qcmian.logviewer

import com.qcmian.logviewer.core.doc.LoadResult
import com.qcmian.logviewer.core.doc.loadDocument
import com.qcmian.logviewer.core.doc.splitLines
import com.qcmian.logviewer.platform.openPathSource
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.measureTimeMillis

/**
 * 1GB 加载性能基准：分阶段计时定位瓶颈。
 * 运行：./gradlew :desktopApp:run --args="bench"
 */
object Bench {
    fun run(benchArgs: List<String>) {
        val path = benchArgs.firstOrNull()?.let { File(it) }?.takeIf { it.exists() }?.absolutePath
            ?: "bench-1g.log"
        val f = File(path)

        if (!f.exists()) {
            println("[0] 生成 1GB 测试文件…")
            val t = measureTimeMillis {
                // ~237B/行 × 400 万行 ≈ 950MB（含中文；控制在 JVM String 1.07B 字符安全线内）
                val line = "03-14 12:00:00.123 E/ActivityManager( 1234): x".repeat(2) +
                    "中文混合内容-" + "abcdefghij".repeat(12) + "tail-padding-data"
                RandomAccessFile(path, "rw").use { raf ->
                    repeat(4_000_000) { raf.writeBytes(line + "\n") }
                }
            }
            println("[0] 生成耗时 ${t}ms")
        }
        val size = f.length()
        println("文件：$path  ${"%.2f".format(size / 1e9)}GB")

        // A. 纯读（Source 1MB 块循环）
        run {
            val opened = openPathSource(path)!!
            var total = 0L
            val t = measureTimeMillis {
                val chunk = ByteArray(1 shl 20)
                opened.source.use { s ->
                    while (true) {
                        val n = s.readAtMostTo(chunk, 0, chunk.size)
                        if (n <= 0) break
                        total += n
                    }
                }
            }
            println("[A] 纯读($total B): ${t}ms")
        }

        // B. 解码（512MB 块 intrinsic，推算全量；注：JVM 单 String 上限 2^30-1 字符，1GB+ 中文整块必崩）
        run {
            val half = ByteArray((size / 2).toInt())
            RandomAccessFile(path, "r").use { it.readFully(half) }
            var len = 0
            val t = measureTimeMillis {
                len = String(half, Charsets.UTF_8).length
            }
            println("[B] 解码(512MB 块 $len chars): ${t}ms → 全量推算 ~${t * 2}ms")
        }

        // C. 切行（同 512MB 块，推算全量）
        run {
            val half = ByteArray((size / 2).toInt())
            RandomAccessFile(path, "r").use { it.readFully(half) }
            val text = String(half, Charsets.UTF_8)
            var n = 0
            val t = measureTimeMillis {
                n = splitLines(text).size
            }
            println("[C] 切行(512MB $n 行): ${t}ms → 全量推算 ~${t * 2}ms")
        }

        // D. 当前流式 loadDocument 整体（真实路径）
        run {
            val opened = openPathSource(path)!!
            var n = 0
            val t = measureTimeMillis {
                val r = kotlinx.coroutines.runBlocking {
                    loadDocument("bench", path, opened.source, opened.sizeBytes, TextEncodingRef) { }
                }
                n = (r as LoadResult.Ok).document.lineCount
            }
            println("[D] loadDocument 流式整体($n 行): ${t}ms")
        }

        // E. 指定编码（跳过检测）
        run {
            val opened = openPathSource(path)!!
            var n = 0
            val t = measureTimeMillis {
                val r = kotlinx.coroutines.runBlocking {
                    loadDocument("bench", path, opened.source, opened.sizeBytes, com.qcmian.logviewer.core.doc.TextEncoding.UTF8) { }
                }
                n = (r as LoadResult.Ok).document.lineCount
            }
            println("[E] loadDocument 指定编码($n 行): ${t}ms")
        }

        // F. 完整链路：加载 + 全量过滤 + 显示序列（用户感知路径）
        run {
            var lineCount = 0
            var matched = 0
            val t = measureTimeMillis {
                val opened = openPathSource(path)!!
                val r = kotlinx.coroutines.runBlocking {
                    loadDocument("bench", path, opened.source, opened.sizeBytes, null) { }
                }
                val doc = (r as LoadResult.Ok).document
                lineCount = doc.lineCount
                // 典型过滤：ERROR 级别（大小写敏感）
                val filter = com.qcmian.logviewer.core.model.FilterConfig(
                    pattern = "ERROR", matchMode = com.qcmian.logviewer.core.model.MatchMode.STRING,
                )
                matched = kotlinx.coroutines.runBlocking {
                    com.qcmian.logviewer.core.engine.runFilter(doc, listOf(filter), 1L, null) { }.matched.size
                }
                // 显示序列构建（"显示命中"模式）
                val display = (0 until matched).map { it }
                if (display.size < 0) lineCount = -1
            }
            println("[F] 完整链路 加载+过滤(ERROR)+显示序列($lineCount 行 / 命中 $matched): ${t}ms")
        }

        // G. 过滤单独计时（已加载状态）
        run {
            val opened = openPathSource(path)!!
            val r = kotlinx.coroutines.runBlocking {
                loadDocument("bench", path, opened.source, opened.sizeBytes, null) { }
            }
            val doc = (r as LoadResult.Ok).document
            val filter = com.qcmian.logviewer.core.model.FilterConfig(
                pattern = "ERROR", matchMode = com.qcmian.logviewer.core.model.MatchMode.STRING,
            )
            com.qcmian.logviewer.core.engine.BenchTrace.enabled = true
            var matched = 0
            val t = measureTimeMillis {
                matched = kotlinx.coroutines.runBlocking {
                    com.qcmian.logviewer.core.engine.runFilter(doc, listOf(filter), 1L, null) { }.matched.size
                }
            }
            com.qcmian.logviewer.core.engine.BenchTrace.enabled = false
            // 第二次过滤（小写全文已缓存，纯检索速度）
            val t2 = measureTimeMillis {
                matched = kotlinx.coroutines.runBlocking {
                    com.qcmian.logviewer.core.engine.runFilter(doc, listOf(filter), 2L, null) { }.matched.size
                }
            }
            println("[G] 全量过滤首次($matched 命中): ${t}ms；二次(纯检索): ${t2}ms")
        }

        // H. 多过滤器组合（3 文本 + 1 排除 + 1 正则；对应多过滤器竞态修复场景）
        run {
            val opened = openPathSource(path)!!
            val r = kotlinx.coroutines.runBlocking {
                loadDocument("bench", path, opened.source, opened.sizeBytes, null) { }
            }
            val doc = (r as LoadResult.Ok).document
            fun f(pattern: String, mode: com.qcmian.logviewer.core.model.MatchMode, exclude: Boolean = false, case: Boolean = false) =
                com.qcmian.logviewer.core.model.FilterConfig(
                    pattern = pattern, matchMode = mode,
                    type = if (exclude) com.qcmian.logviewer.core.model.FilterType.EXCLUDE else com.qcmian.logviewer.core.model.FilterType.INCLUDE,
                    caseSensitive = case,
                )
            val filters = listOf(
                f("ERROR", com.qcmian.logviewer.core.model.MatchMode.STRING),
                f("warn", com.qcmian.logviewer.core.model.MatchMode.STRING, case = false),
                f("中文混合", com.qcmian.logviewer.core.model.MatchMode.STRING),
                f("padding", com.qcmian.logviewer.core.model.MatchMode.STRING, exclude = true),
                f("E/[A-Za-z]+", com.qcmian.logviewer.core.model.MatchMode.REGEX),
            )
            var matched = 0
            val t = measureTimeMillis {
                matched = kotlinx.coroutines.runBlocking {
                    com.qcmian.logviewer.core.engine.runFilter(doc, filters, 3L, null) { }.matched.size
                }
            }
            println("[H] 多过滤器组合(5 规则: 3文本+1排除+1正则) 命中 $matched: ${t}ms")
        }

        // I. 正则单规则全量（正则路径基线）
        run {
            val opened = openPathSource(path)!!
            val r = kotlinx.coroutines.runBlocking {
                loadDocument("bench", path, opened.source, opened.sizeBytes, null) { }
            }
            val doc = (r as LoadResult.Ok).document
            val filter = com.qcmian.logviewer.core.model.FilterConfig(
                pattern = "E/[A-Za-z]+", matchMode = com.qcmian.logviewer.core.model.MatchMode.REGEX,
            )
            var matched = 0
            val t = measureTimeMillis {
                matched = kotlinx.coroutines.runBlocking {
                    com.qcmian.logviewer.core.engine.runFilter(doc, listOf(filter), 4L, null) { }.matched.size
                }
            }
            println("[I] 正则单规则全量 命中 $matched: ${t}ms")
        }

        // J. 查找高亮路径（滚动一帧 50 行 × 60 帧 = 3000 次 rangesIn）
        //    旧实现：每次 new SearchSpec（正则=每行编译）；新实现：spec 复用缓存
        run {
            val opened = openPathSource(path)!!
            val r = kotlinx.coroutines.runBlocking {
                loadDocument("bench", path, opened.source, opened.sizeBytes, null) { }
            }
            val doc = (r as LoadResult.Ok).document
            // 取前 50 行做样本
            val sample = ArrayList<String>(50)
            for (i in 0 until 50) sample.add(doc.line(i))

            // J1 旧行为（文本模式）
            val tOldText = measureTimeMillis {
                repeat(3000) { i ->
                    com.qcmian.logviewer.core.engine.SearchSpec("Activity", false, true).rangesIn(sample[i % 50])
                }
            }
            // J2 新行为（文本模式，spec 复用）
            val specText = com.qcmian.logviewer.core.engine.SearchSpec("Activity", false, true)
            val tNewText = measureTimeMillis {
                repeat(3000) { i -> specText.rangesIn(sample[i % 50]) }
            }
            // J3 旧行为（正则模式：每行重新编译正则）
            val tOldRegex = measureTimeMillis {
                repeat(3000) { i ->
                    com.qcmian.logviewer.core.engine.SearchSpec("E/[A-Za-z]+", true, true).rangesIn(sample[i % 50])
                }
            }
            // J4 新行为（正则模式，spec 复用）
            val specRegex = com.qcmian.logviewer.core.engine.SearchSpec("E/[A-Za-z]+", true, true)
            val tNewRegex = measureTimeMillis {
                repeat(3000) { i -> specRegex.rangesIn(sample[i % 50]) }
            }
            println("[J] 查找高亮(3000 次=一帧50行×60帧): 文本 旧=${tOldText}ms 新=${tNewText}ms (×${"%.0f".format(tOldText.toDouble() / tNewText.coerceAtLeast(1))}加速)；正则 旧=${tOldRegex}ms 新=${tNewRegex}ms (×${"%.0f".format(tOldRegex.toDouble() / tNewRegex.coerceAtLeast(1))}加速)")
        }

        // K. 端到端：模拟真实打开时序（点击打开 → UI 可渲染首屏）
        //    加载 → 首次过滤（FilterConfig 默认忽略大小写 → 触发 lowerText 构建）→ 首屏 50 行数据
        run {
            val opened = openPathSource(path)!!
            var loadMs = 0L
            var filterMs = 0L
            var renderMs = 0L
            var lineCount = 0
            var matched = 0

            // 1) 加载（真实 loadRef 路径）
            val doc: com.qcmian.logviewer.core.doc.DocumentSource
            loadMs = kotlin.system.measureTimeMillis {
                val r = kotlinx.coroutines.runBlocking {
                    loadDocument("bench", path, opened.source, opened.sizeBytes, null) { }
                }
                doc = (r as LoadResult.Ok).document
                lineCount = doc.lineCount
            }

            // 2) 首次过滤：默认规则（包含 + 忽略大小写，FilterConfig 默认值）——真实会话恢复场景
            val filter = com.qcmian.logviewer.core.model.FilterConfig(
                pattern = "ERROR", matchMode = com.qcmian.logviewer.core.model.MatchMode.STRING,
                caseSensitive = false,
            )
            com.qcmian.logviewer.core.engine.BenchTrace.enabled = true
            filterMs = kotlin.system.measureTimeMillis {
                matched = kotlinx.coroutines.runBlocking {
                    com.qcmian.logviewer.core.engine.runFilter(doc, listOf(filter), 9L, null) { }.matched.size
                }
            }
            com.qcmian.logviewer.core.engine.BenchTrace.enabled = false

            // 3) 首屏 50 行渲染数据（displayAt + textOf 等价工作量）
            renderMs = kotlin.system.measureTimeMillis {
                repeat(50) { pos ->
                    val idx = (pos * (matched / 50).coerceAtLeast(1)).coerceAtMost(lineCount - 1)
                    doc.line(idx)
                }
            }

            println("[K] 端到端(打开→首屏可渲染): 加载=${loadMs}ms + 首次过滤=${filterMs}ms + 首屏数据=${renderMs}ms ≈ 总计 ${loadMs + filterMs + renderMs}ms（$lineCount 行 / 命中 $matched）")
        }
    }

    private val TextEncodingRef: com.qcmian.logviewer.core.doc.TextEncoding? = null
}
