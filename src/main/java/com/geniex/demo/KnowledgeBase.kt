package com.geniex.demo

import android.util.Log
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher

/**
 * 离线百科检索：libkiwix + libzim 的 ZIM 包全文检索。
 * 只取命中的少数摘要注入 prompt，不占额外上下文。
 */
object KnowledgeBase {
    private const val TAG = "GenieXDemo"

    private var libsLoaded = false
    private var archive: Archive? = null
    private var searcher: Searcher? = null

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
            ready = true
            Log.i(TAG, "KB opened entries=${a.entryCount}")
            runCatching {
                val probe = search("孔子", 2)
                Log.i(TAG, "KB selfTest hits=${probe.size} first=${probe.firstOrNull()?.first}")
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
        runCatching { archive?.dispose() }
        searcher = null
        archive = null
        ready = false
    }

    /** 返回 (标题, 摘要)，摘要已去 HTML 标签 */
    @Synchronized
    fun search(
        query: String,
        limit: Int = 3,
    ): List<Pair<String, String>> {
        val s = searcher ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return runCatching {
            val result = s.search(Query(query.trim()))
            val total = runCatching { result.estimatedMatches.toInt() }.getOrDefault(0)
            Log.i(TAG, "KB query=$query est=$total")
            if (total <= 0) return emptyList()
            val iterator = result.getResults(0, limit)
            val out = ArrayList<Pair<String, String>>(limit)
            var i = 0
            while (i < limit) {
                val hasNext = runCatching { iterator.hasNext() }.getOrDefault(false)
                if (!hasNext) break
                val entry = runCatching { iterator.next() }.getOrNull()
                val title = entry?.title?.takeIf { it.isNotBlank() }
                    ?: runCatching { iterator.title }.getOrNull().orEmpty()
                val snippet = runCatching { stripHtml(iterator.snippet ?: "") }.getOrDefault("")
                if (title.isNotBlank() && snippet.isNotBlank()) out.add(title to snippet)
                i++
            }
            Log.i(TAG, "KB got=${out.size}")
            out
        }.getOrElse {
            Log.e(TAG, "KB search failed: $query", it)
            emptyList()
        }
    }

    private fun stripHtml(src: String): String =
        src.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
}
