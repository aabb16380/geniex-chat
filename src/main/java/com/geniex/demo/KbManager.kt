package com.geniex.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 数据库（离线百科 ZIM）管理：列出 / 下载 / 切换。
 */
object KbManager {
    private const val TAG = "GenieXDemo"
    private const val PREF = "geniex_ui"
    private const val KEY_ACTIVE = "kb_active_id"

    data class Entry(
        val id: String,
        val label: String,
        val sizeMb: Int,
        val url: String,
    )

    val catalog =
        listOf(
            Entry("zh_top_mini", "中文维基 · 精选导言版", 264, "https://download.kiwix.org/zim/wikipedia/wikipedia_zh_top_mini_2026-09.zim"),
            Entry("zh_top_nopic", "中文维基 · 精选完整版", 1479, "https://download.kiwix.org/zim/wikipedia/wikipedia_zh_top_nopic_2026-09.zim"),
            Entry("zh_top_maxi", "中文维基 · 精选含图版", 3000, "https://download.kiwix.org/zim/wikipedia/wikipedia_zh_top_maxi_2026-09.zim"),
        )

    private fun dir(ctx: Context): File = File(ctx.filesDir, "kb").apply { mkdirs() }

    fun fileOf(
        ctx: Context,
        id: String,
    ): File = File(dir(ctx), "$id.zim")

    fun isDownloaded(
        ctx: Context,
        e: Entry,
    ): Boolean = fileOf(ctx, e.id).let { it.exists() && it.length() > 1_000_000 }

    fun activeId(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_ACTIVE, "") ?: ""

    /** 启动时打开上次选择的库；没有记录则用第一个已下载的 */
    fun openActive(ctx: Context): Boolean {
        val saved = activeId(ctx)
        val candidate =
            catalog.firstOrNull { it.id == saved && isDownloaded(ctx, it) }
                ?: catalog.firstOrNull { isDownloaded(ctx, it) }
                ?: return false
        val ok = KnowledgeBase.open(fileOf(ctx, candidate.id).absolutePath)
        if (ok) ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, candidate.id).apply()
        Log.i(TAG, "KB openActive id=${candidate.id} ok=$ok")
        return ok
    }

    fun showDialog(activity: Activity) {
        val ctx: Context = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        }
        val dialog = AlertDialog.Builder(ctx).setTitle("数据库管理").setView(root).setNegativeButton("关闭", null).create()

        fun rebuild() {
            root.removeAllViews()
            val active = activeId(ctx)
            catalog.forEach { e ->
                val downloaded = isDownloaded(ctx, e)
                val isActive = downloaded && e.id == active
                val row = TextView(ctx).apply {
                    text =
                        buildString {
                            append(if (isActive) "✅ " else if (downloaded) "📦 " else "⬇️ ")
                            append(e.label)
                            append("　")
                            append(if (downloaded) "已下载 ${fileOf(ctx, e.id).length() / 1024 / 1024}MB" else "未下载 ${e.sizeMb}MB")
                        }
                    textSize = 14f
                    setPadding(dp(ctx, 10), dp(ctx, 12), dp(ctx, 10), dp(ctx, 12))
                    setTextColor(if (isActive) Color.parseColor("#2F6BFF") else Color.parseColor("#1F1F1F"))
                    setOnClickListener {
                        if (!downloaded) {
                            download(activity, e) { rebuild() }
                        } else {
                            val ok = KnowledgeBase.open(fileOf(ctx, e.id).absolutePath)
                            if (ok) {
                                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, e.id).apply()
                                Toast.makeText(ctx, "已切换到：${e.label}", Toast.LENGTH_SHORT).show()
                                rebuild()
                            } else {
                                Toast.makeText(ctx, "打开失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                root.addView(row)
            }
        }

        rebuild()
        dialog.show()
    }

    private fun download(
        activity: Activity,
        e: Entry,
        onDone: () -> Unit,
    ) {
        val ctx: Context = activity
        val target = File(dir(ctx), "${e.id}.zim.tmp")
        val progress = TextView(ctx).apply {
            text = "准备下载…"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 4))
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
            addView(progress)
            addView(bar)
        }
        val dlg =
            AlertDialog
                .Builder(ctx)
                .setTitle("下载 ${e.label}")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", null)
                .create()
        dlg.show()

        Thread {
            try {
                val conn = (URL(e.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (done - last > 4 * 1024 * 1024) {
                                last = done
                                val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                                activity.runOnUiThread {
                                    bar.progress = pct
                                    progress.text = "${done / 1024 / 1024} / ${total / 1024 / 1024} MB · $pct%"
                                }
                            }
                        }
                    }
                }
                val finalFile = fileOf(ctx, e.id)
                if (finalFile.exists()) finalFile.delete()
                target.renameTo(finalFile)
                activity.runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(ctx, "下载完成：${e.label}", Toast.LENGTH_SHORT).show()
                    onDone()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "KB download failed", t)
                activity.runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(ctx, "下载失败：${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dp(
        ctx: Context,
        v: Int,
    ): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
