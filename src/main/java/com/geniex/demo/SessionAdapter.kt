// ---------------------------------------------------------------------
// 侧边栏会话列表适配器：标题 + 删除按钮
// 由 DSH 添加
// ---------------------------------------------------------------------
package com.geniex.demo

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView

class SessionAdapter(
    private val context: Context,
    private val items: MutableList<ChatSession>,
    private val onDelete: (ChatSession) -> Unit,
    private val onRename: (ChatSession) -> Unit,
) : BaseAdapter() {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): ChatSession = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    fun setData(list: List<ChatSession>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup?,
    ): View {
        val view =
            convertView ?: LayoutInflater.from(context).inflate(R.layout.item_session, parent, false)
        val session = items[position]
        view.findViewById<TextView>(R.id.tv_session_name).text = session.title
        view.findViewById<ImageButton>(R.id.btn_delete_session).setOnClickListener {
            onDelete(session)
        }
        view.findViewById<ImageButton>(R.id.btn_rename_session).setOnClickListener {
            onRename(session)
        }
        return view
    }
}
