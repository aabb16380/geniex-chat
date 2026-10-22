// ---------------------------------------------------------------------
// 对话历史持久化：把文本对话存到 filesDir/chat_history.json
// 由 DSH 添加，用于 App 重启后恢复对话
// ---------------------------------------------------------------------
package com.geniex.demo

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedMessage(
    val role: String,
    val content: String,
)

object ChatHistory {
    private const val FILE_NAME = "chat_history.json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(
        context: Context,
        items: List<SavedMessage>,
    ) {
        try {
            if (items.isEmpty()) {
                clear(context)
                return
            }
            file(context).writeText(json.encodeToString(items))
        } catch (e: Exception) {
            Log.e("ChatHistory", "save failed", e)
        }
    }

    fun load(context: Context): List<SavedMessage> =
        try {
            val f = file(context)
            if (!f.exists()) {
                emptyList()
            } else {
                val text = f.readText()
                if (text.isBlank()) emptyList() else json.decodeFromString<List<SavedMessage>>(text)
            }
        } catch (e: Exception) {
            Log.e("ChatHistory", "load failed", e)
            emptyList()
        }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}
