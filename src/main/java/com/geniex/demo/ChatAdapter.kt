// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.File

data class Message(
    val content: String,
    val type: MessageType,
    val images: List<File> = emptyList(),
    val audio: List<File> = emptyList(),
    /** 流式生成中：只用纯文本渲染，结束后再走 Markdown，避免表格反复重排 */
    val streaming: Boolean = false,
    /** 思考耗时（秒），0 表示未知（历史消息） */
    val thinkSeconds: Int = 0,
)

enum class MessageType(
    val value: Int,
) {
    USER(0),
    ASSISTANT(1),
    PROFILE(2),
    IMAGES(3),
    ASSISTANT_IMAGES(4),
    LOADING(5),
    REFS(6),
    ;

    companion object {
        fun from(value: Int): MessageType = entries.firstOrNull { it.value == value } ?: PROFILE
    }
}

private val thinkExpanded = mutableMapOf<Int, Boolean>()

class ChatAdapter(
    private val messages: List<Message>,
    private val onLongClick: ((Int) -> Unit)? = null,
    private val onItemClick: ((Int) -> Unit)? = null,
    private val onRefsClick: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var selectionMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    val selected = sortedSetOf<Int>()
    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return message.type.value
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val type = MessageType.from(viewType)
        return when (type) {
            MessageType.USER -> {
                UserViewHolder(inflater.inflate(R.layout.item_user_message, parent, false))
            }

            MessageType.ASSISTANT -> {
                AiViewHolder(inflater.inflate(R.layout.item_ai_message, parent, false))
            }

            MessageType.IMAGES -> {
                ImagesViewHolder(inflater.inflate(R.layout.item_image_message, parent, false))
            }

            MessageType.ASSISTANT_IMAGES -> {
                ImagesViewHolder(
                    inflater.inflate(
                        R.layout.item_assistant_image_message,
                        parent,
                        false,
                    ),
                )
            }

            MessageType.REFS -> {
                RefsViewHolder(inflater.inflate(R.layout.item_refs_message, parent, false))
            }

            MessageType.LOADING -> {
                LoadingViewHolder(
                    inflater.inflate(R.layout.item_loading_message, parent, false),
                )
            }

            else -> {
                ProfileViewHolder(inflater.inflate(R.layout.item_profile_message, parent, false))
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        val message = messages[position]
        // 模型信息行（PROFILE）不可多选，也不显示勾选框
        val selectable =
            message.type == MessageType.USER ||
                message.type == MessageType.ASSISTANT
        val tvMsg = holder.itemView.findViewById<android.widget.TextView>(R.id.tv_message)
        // 参考资料行是按钮，不能开文本选择（会吞掉点击）
        if (message.type != MessageType.REFS) tvMsg?.setTextIsSelectable(!selectionMode)
        val cb = holder.itemView.findViewById<android.widget.CheckBox>(R.id.cb_select)
        if (cb != null) {
            cb.visibility =
                if (selectionMode && selectable) android.view.View.VISIBLE else android.view.View.GONE
            cb.isChecked = selected.contains(position)
        }
        holder.itemView.setOnClickListener {
            if (selectionMode) onItemClick?.invoke(position)
        }
        holder.itemView.setOnLongClickListener {
            if (selectable) {
                onLongClick?.invoke(position)
                true
            } else {
                false
            }
        }
        if (holder is UserViewHolder) holder.bind(message)
        if (holder is AiViewHolder) holder.bind(message, position)
        if (holder is ImagesViewHolder) holder.bind(message)
        if (holder is ProfileViewHolder) holder.bind(message)
        if (holder is RefsViewHolder) {
            holder.bind(message) { onRefsClick?.invoke(position) }
            holder.itemView.setOnClickListener { onRefsClick?.invoke(position) }
        }
    }

    override fun getItemCount() = messages.size

    class UserViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }
    }

    class AiViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)
        private val markwon: Markwon =
            Markwon
                .builder(itemView.context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(itemView.context))
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(
                    JLatexMathPlugin.create(tvMessage.textSize) { builder ->
                        builder.inlinesEnabled(true)
                        builder.blocksEnabled(true)
                    },
                ).build()

        private val llBlocks: android.widget.LinearLayout = itemView.findViewById(R.id.ll_blocks)
        private val tvThinkToggle: TextView = itemView.findViewById(R.id.tv_think_toggle)
        private val tvThinkBody: TextView = itemView.findViewById(R.id.tv_think_body)

        /** 拆分  thinking...<｜end▁of▁thinking｜> 思维链与正文 */
        private val thinkOpens = listOf(" thinking", "<think>", "<|begin_of_thought|>", "[think]")
        private val thinkCloses = listOf("<｜end▁of▁thinking｜>", "</think>", "<|end_of_thought|>", "[/think]")

        /** 拆分思维链：兼容  thinking / <think> / <|begin_of_thought|> 多种标记，标记本身不显示 */
        private fun splitThink(src: String): Pair<String, String> {
            var openIdx = -1
            var openTag = ""
            for (tag in thinkOpens) {
                val i = src.indexOf(tag, ignoreCase = true)
                if (i >= 0 && (openIdx < 0 || i < openIdx)) {
                    openIdx = i
                    openTag = tag
                }
            }
            if (openIdx < 0) return "" to src
            val prefix = src.substring(0, openIdx)
            val after = src.substring(openIdx + openTag.length)
            var closeIdx = -1
            var closeLen = 0
            for (tag in thinkCloses) {
                val i = after.indexOf(tag, ignoreCase = true)
                if (i >= 0 && (closeIdx < 0 || i < closeIdx)) {
                    closeIdx = i
                    closeLen = tag.length
                }
            }
            return if (closeIdx < 0) {
                after to prefix.trim()
            } else {
                after.substring(0, closeIdx) to (prefix + after.substring(closeIdx + closeLen)).trim()
            }
        }
        /** 统一 LaTeX 定界符，并去掉 $ 内侧空格（否则 Markwon 不认行内公式） */
        private fun normalizeMath(s: String): String {
            val unified =
                s.replace("\\(", "$").replace("\\)", "$")
                    .replace("\\[", "$$").replace("\\]", "$$")
            val parts = unified.split("$$").toMutableList()
            val inline = Regex("\\$\\s*([^$\\n]+?)\\s*\\$")
            for (i in parts.indices) {
                if (i % 2 == 0) {
                    parts[i] =
                        inline.replace(parts[i]) { m ->
                            "$$" + m.groupValues[1].trim() + "$$"
                        }
                }
            }
            return parts.joinToString("$$")
        }

        fun bind(
            message: Message,
            position: Int,
        ) {
            val (think, bodyRaw) = splitThink(message.content)
            val hasThink = think.isNotBlank()
            if (hasThink) {
                // 用户手动点过就以他的选择为准；没点过时：还在思考就展开，出正文就收起
                val expanded = thinkExpanded[position] ?: bodyRaw.isBlank()
                tvThinkToggle.visibility = View.VISIBLE
                val base = if (expanded) itemView.context.getString(R.string.think_expanded) else itemView.context.getString(R.string.think_collapsed)
                tvThinkToggle.text = if (message.thinkSeconds > 0) "$base ${message.thinkSeconds}s" else base
                if (message.streaming) {
                    // 生成中纯文本，避免每帧解析公式造成卡顿
                    tvThinkBody.text = think.trim()
                } else {
                    // 结束后用 Markwon 渲染，思考里的公式也能正常显示
                    markwon.setMarkdown(tvThinkBody, normalizeMath(think.trim()))
                }
                tvThinkBody.visibility = if (expanded) View.VISIBLE else View.GONE
                tvThinkToggle.setOnClickListener {
                    thinkExpanded[position] = !expanded
                    (itemView.parent as? RecyclerView)?.adapter?.notifyItemChanged(position)
                }
            } else {
                tvThinkToggle.visibility = View.GONE
                tvThinkToggle.setOnClickListener(null)
                tvThinkBody.visibility = View.GONE
            }
            val body = bodyRaw.trim()
            if (message.streaming) {
                // 生成中：纯文本；只显示正文部分，思考内容只在折叠区里
                llBlocks.visibility = View.GONE
                llBlocks.removeAllViews()
                tvMessage.text = body
                tvMessage.visibility = if (body.isBlank()) View.GONE else View.VISIBLE
            } else {
                val content = normalizeMath(body.trim())
                if (!content.contains("$$")) {
                    tvMessage.visibility = View.VISIBLE
                    llBlocks.visibility = View.GONE
                    llBlocks.removeAllViews()
                    markwon.setMarkdown(tvMessage, content)
                } else {
                    // 含独立公式：文字走 Markwon，公式各自放进横向滚动容器
                    tvMessage.visibility = View.GONE
                    llBlocks.visibility = View.VISIBLE
                    llBlocks.removeAllViews()
                    val ctx = itemView.context
                    val textColor = tvMessage.currentTextColor
                    val textSize = tvMessage.textSize
                    content.split("$$").forEachIndexed { index, raw ->
                        if (index % 2 == 1) {
                            val scroll = android.widget.HorizontalScrollView(ctx)
                            scroll.isHorizontalScrollBarEnabled = true
                            val tv = TextView(ctx)
                            tv.textSize = 0f
                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize)
                            tv.setTextColor(textColor)
                            tv.setPadding(0, 8, 0, 8)
                            markwon.setMarkdown(tv, "$$" + raw + "$$")
                            scroll.addView(tv)
                            llBlocks.addView(scroll)
                        } else if (raw.isNotBlank()) {
                            val tv = TextView(ctx)
                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize)
                            tv.setTextColor(textColor)
                            tv.setTextIsSelectable(true)
                            markwon.setMarkdown(tv, raw)
                            llBlocks.addView(tv)
                        }
                    }
                }
            }
            tvMessage.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    class ProfileViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }

        private fun dpToPx(
            dp: Int,
            context: android.content.Context,
        ): Int = (dp * context.resources.displayMetrics.density).toInt()
    }

    class RefsViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(
            message: Message,
            onClick: () -> Unit,
        ) {
            tvMessage.setTextIsSelectable(false)
            tvMessage.isClickable = true
            val count =
                runCatching { org.json.JSONArray(message.content).length() }.getOrDefault(0)
            tvMessage.text =
                itemView.context.getString(R.string.refs_button, count)
            tvMessage.setOnClickListener { onClick() }
        }
    }

    class LoadingViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView)

    class ImagesViewHolder(
        itemView: View,
    ) : RecyclerView.ViewHolder(itemView) {
        private val imageContainer: LinearLayout = itemView.findViewById(R.id.image_container)

        fun bind(message: Message) {
            val savedImageFiles = message.images
            imageContainer.removeAllViews()
            val context = itemView.context

            for (file in savedImageFiles) {
                val itemView =
                    LayoutInflater
                        .from(context)
                        .inflate(R.layout.item_image_item_message, imageContainer, false)
                val ivImage = itemView.findViewById<ImageView>(R.id.iv_image)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    ivImage.setImageBitmap(bitmap)
                }
                imageContainer.addView(itemView)
            }
        }
    }
}
