package com.geniex.demo

import android.util.Log
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher

/**
 * 离线百科检索：libkiwix + libzim 的 ZIM 包全文/标题检索。
 *
 * 召回顺序（越靠前越可信）：
 *  1. 标题精确命中：原句、去掉「介绍一下 / 是什么」等前后缀
 *  2. 中文里「最长的可命中标题子串」（「介绍一下孔子是谁」→「孔子」）
 *  3. 标题建议 SuggestionSearcher（按前缀，最贴近用户想查的条目）
 *  4. 全文检索兜底，再按「标题相关度」重排
 *
 * 同时会剔除 system / 什么 / 介绍 这类噪声词，避免「Linux system」被
 * 「GNU/Linux命名爭議」这种只在正文里出现的条目挤掉真正的主条目。
 */
object KnowledgeBase {
    private const val TAG = "GenieXDemo"

    private var libsLoaded = false
    private var archive: Archive? = null
    private var searcher: Searcher? = null
    private var suggester: SuggestionSearcher? = null

    var ready = false
        private set

    /** 手动加载 JNI 库（AAR 未提供自动加载器） */
    @Synchronized
    private fun loadLibs(): Boolean {
        if (libsLoaded) return true
        libsLoaded =
            runCatching {
                System.loadLibrary("c++_shared")
                System.loadLibrary("zim")
                System.loadLibrary("zim_wrapper")
                System.loadLibrary("kiwix")
                System.loadLibrary("kiwix_wrapper")
                true
            }.getOrElse {
                Log.e(TAG, "loadLibs failed", it)
                false
            }
        return libsLoaded
    }

    @Synchronized
    fun open(path: String): Boolean {
        close()
        if (!loadLibs()) return false
        return runCatching {
            val a = Archive(path)
            archive = a
            searcher = Searcher(a)
            suggester = runCatching { SuggestionSearcher(a) }.getOrNull()
            ready = true
            Log.i(TAG, "KB opened entries=${a.entryCount} suggestion=${suggester != null}")
            runCatching {
                val probes = listOf("孔子", "Linux system", "美国为什么要偷袭珍珠港")
                Log.i(
                    TAG,
                    "KB selfTest " +
                        probes.joinToString("; ") { pq -> "$pq -> ${search(pq, 2).map { it.first }}" },
                )
            }
            true
        }.getOrElse {
            Log.e(TAG, "KB open failed: $path", it)
            ready = false
            false
        }
    }

    @Synchronized
    fun close() {
        runCatching { searcher?.dispose() }
        runCatching { suggester?.dispose() }
        runCatching { archive?.dispose() }
        searcher = null
        suggester = null
        archive = null
        ready = false
    }

    /** 返回 (标题, 摘要)，摘要已去 HTML 标签 */
    @Synchronized
    fun search(
        query: String,
        limit: Int = 3,
    ): List<Pair<String, String>> {
        val a = archive ?: return emptyList()
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val terms = keywords(q)
        val picked = LinkedHashMap<String, String>() // 标题 -> 摘要

        fun take(
            title: String,
            snippet: String,
        ) {
            val t = title.trim()
            if (t.isBlank() || picked.size >= limit) return
            if (picked.keys.any { it.equals(t, ignoreCase = true) }) return
            picked[t] = snippet
        }

        // 1) 标题精确命中
        for (cand in titleCandidates(a, q)) {
            if (picked.size >= limit) break
            val hit =
                runCatching {
                    if (a.hasEntryByTitle(cand)) a.getEntryByTitle(cand).title else null
                }.getOrNull()
            if (!hit.isNullOrBlank()) take(hit, "")
        }

        // 2) 标题建议（前缀匹配，最贴近用户想查的条目）
        val ss = suggester
        if (ss != null && picked.size < limit) {
            for (key in (listOf(q) + terms)) {
                if (picked.size >= limit) break
                val sugg = runCatching { ss.suggest(key) }.getOrNull() ?: continue
                runCatching {
                    val it = sugg.getResults(0, limit + 2)
                    var guard = 0
                    while (guard < limit + 2 && picked.size < limit) {
                        if (!runCatching { it.hasNext() }.getOrDefault(false)) break
                        val item = runCatching { it.next() }.getOrNull() ?: break
                        val title = item.title.orEmpty()
                        if (title.isNotBlank()) {
                            val hasSnip = runCatching { item.hasSnippet() }.getOrDefault(false)
                            val snip =
                                if (hasSnip) stripHtml(item.snippet ?: "") else ""
                            take(title, snip)
                        }
                        guard++
                    }
                    runCatching { it.dispose() }
                }
                runCatching { sugg.dispose() }
            }
        }

        // 3) 全文检索兜底：多取候选，按标题相关度重排
        val s = searcher
        if (s != null && picked.size < limit) {
            val fullTextQuery = if (terms.isNotEmpty()) terms.joinToString(" ") else q
            runCatching {
                val res = s.search(Query(fullTextQuery))
                val iter = res.getResults(0, 12)
                val scored = ArrayList<Triple<Int, String, String>>()
                var i = 0
                while (i < 12) {
                    if (!runCatching { iter.hasNext() }.getOrDefault(false)) break
                    val entry = runCatching { iter.next() }.getOrNull()
                    if (entry != null) {
                        val title = entry.title.orEmpty()
                        val snip = runCatching { stripHtml(iter.snippet ?: "") }.getOrDefault("")
                        val score = scoreOf(title, snip, terms)
                        if (title.isNotBlank() && score > 0) scored.add(Triple(score, title, snip))
                    }
                    i++
                }
                scored.sortByDescending { it.first }
                scored.forEach { (_, t, sn) -> take(t, sn) }
                Log.i(TAG, "KB fulltext q='$fullTextQuery' cand=${scored.size}")
            }.onFailure { Log.e(TAG, "KB fulltext failed", it) }
        }

        Log.i(TAG, "KB query='$q' terms=$terms -> ${picked.keys.toList()}")
        return picked.map { it.key to it.value }
    }

    // ---------------- 关键词与候选 ----------------

    private val CN_PREFIXES =
        listOf(
            "请问一下", "请问", "帮我", "介绍一下", "介绍", "讲讲", "说说",
            "解释一下", "解释", "什么是", "是什么", "告诉我", "我想知道", "想知道",
            "关于", "如何", "怎么", "怎样", "为什么",
        )

    private val CN_SUFFIXES =
        listOf("是什么意思", "是谁", "是什么", "怎么样", "呢", "吗", "啊", "呀")

    private val STOPWORDS =
        setOf(
            "a", "an", "the", "is", "are", "was", "were", "be", "of", "and", "or", "to", "in",
            "on", "at", "for", "with", "about", "what", "who", "whom", "which", "how", "why",
            "when", "where", "please", "tell", "me", "explain", "introduce", "describe",
            "system", "info", "information", "more", "detail", "details",
            "什么", "是", "的", "了", "吗", "呢", "谁", "介绍", "一下", "请问", "如何",
            "为什么", "怎么", "怎样", "哪些", "关于", "请", "帮", "我", "讲", "说说",
            "解释", "资料", "内容", "知道", "告诉",
        )

    private fun keywords(query: String): List<String> =
        query
            .lowercase()
            .split(Regex("[\\s,，。！？!?；;：:、\"'“”‘’()（）\\[\\]【】<>]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()

    /** 生成待验证的标题候选：原句 → 去前后缀 → 中文最长命中子串 → 英文关键词 */
    private fun titleCandidates(
        a: Archive,
        q: String,
    ): List<String> {
        val out = LinkedHashSet<String>()
        out.add(q)
        trimCnAffixes(q)?.let { out.add(it) }

        if (q.any { it.isCjk() }) {
            val len = q.length
            outer@
            for (l in minOf(8, len) downTo 2) {
                for (start in 0..(len - l)) {
                    val sub = q.substring(start, start + l)
                    if (!sub.any { it.isCjk() }) continue
                    val ok =
                        runCatching { a.hasEntryByTitle(sub) }.getOrDefault(false)
                    if (ok) {
                        out.add(sub)
                        if (out.size >= 4) break@outer
                    }
                }
            }
        }
        keywords(q).forEach { out.add(it) }
        return out.toList()
    }

    private fun trimCnAffixes(q: String): String? {
        var s = q
        var changed = false
        for (p in CN_PREFIXES) {
            if (s.length > p.length && s.startsWith(p)) {
                s = s.removePrefix(p)
                changed = true
                break
            }
        }
        for (x in CN_SUFFIXES) {
            if (s.length > x.length && s.endsWith(x)) {
                s = s.removeSuffix(x)
                changed = true
                break
            }
        }
        return if (changed && s.isNotBlank()) s.trim() else null
    }

    private fun Char.isCjk(): Boolean = code in 0x3400..0x9FFF

    /** 标题相关度打分：命中标题最强，只在正文出现最弱；标题越短越像主条目 */
    private fun scoreOf(
        title: String,
        snippet: String,
        terms: List<String>,
    ): Int {
        if (terms.isEmpty()) return if (title.isNotBlank()) 1 else 0
        val t = title.lowercase()
        val s = snippet.lowercase()
        var score = 0
        for (k in terms) {
            val kl = k.lowercase()
            when {
                t == kl -> score += 100
                t.startsWith(kl) -> score += 60
                t.contains(kl) -> score += 40
                s.contains(kl) -> score += 15
            }
        }
        if (score == 0) return 0
        return score + (24 - title.length).coerceAtLeast(0)
    }

    private fun stripHtml(src: String): String =
        src.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---------------- 正文读取 ----------------

    /**
     * 取某条目的正文（纯文本），用于「参考资料」详情与摘要。
     * 先按标题精确查，再按路径查；重定向会跟进；HTML 会压成带段落换行的纯文本。
     */
    @Synchronized
    fun article(
        title: String,
        limit: Int = 12000,
    ): String? {
        val a = archive ?: return null
        if (title.isBlank()) return null
        return runCatching {
            var entry =
                when {
                    a.hasEntryByTitle(title) -> a.getEntryByTitle(title)
                    a.hasEntryByPath(title) -> a.getEntryByPath(title)
                    else -> null
                }
            if (entry == null) return null
            var guard = 0
            while (entry != null && entry.isRedirect && guard < 5) {
                entry = runCatching { entry.getRedirectEntry() }.getOrNull()
                guard++
            }
            val item = entry?.getItem(true) ?: return null
            val mime = runCatching { item.mimetype }.getOrDefault("")
            val bytes = runCatching { item.data?.data }.getOrNull() ?: return null
            val raw = String(bytes, Charsets.UTF_8)
            val text = if (mime.contains("html", ignoreCase = true)) stripHtmlToText(raw) else raw.trim()
            Log.d(TAG, "KB article title=$title mime=$mime raw=${raw.length} text=${text.length}")
            text.take(limit).ifBlank { null }
        }.getOrElse {
            Log.e(TAG, "KB article failed: $title", it)
            null
        }
    }

    /**
     * HTML -> 纯文本。先剥掉 head/style/script/注释，再交给系统的 Html.fromHtml，
     * 比手写正则可靠（维基页面的结构很杂，正则容易把正文连带吃掉）。
     */
    private fun stripHtmlToText(src: String): String {
        val cleaned =
            src
                .replace(Regex("(?is)<!--.*?-->"), " ")
                .replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        val text =
            runCatching {
                android.text.Html.fromHtml(cleaned, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            }.getOrElse { cleaned.replace(Regex("<[^>]+>"), " ") }
        return text
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n[ \\t]*\\n[ \\t]*\\n+"), "\n\n")
            .trim()
    }
}
