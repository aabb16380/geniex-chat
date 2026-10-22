// ---------------------------------------------------------------------
// 多会话存档：sessions.json 里保存所有历史对话
// 由 DSH 添加
// ---------------------------------------------------------------------
package com.geniex.demo

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turns: List<SavedMessage> = emptyList(),
)

object SessionStore {
    private const val FILE_NAME = "sessions.json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(
        context: Context,
        sessions: List<ChatSession>,
    ) {
        try {
            if (sessions.isEmpty()) {
                file(context).delete()
                return
            }
            file(context).writeText(json.encodeToString(sessions))
        } catch (e: Exception) {
            Log.e("SessionStore", "save failed", e)
        }
    }

    fun load(context: Context): List<ChatSession> =
        try {
            val f = file(context)
            if (!f.exists()) {
                emptyList()
            } else {
                val text = f.readText()
                if (text.isBlank()) emptyList() else json.decodeFromString<List<ChatSession>>(text)
            }
        } catch (e: Exception) {
            Log.e("SessionStore", "load failed", e)
            emptyList()
        }
}
