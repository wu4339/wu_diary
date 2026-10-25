package com.wu.diary.model

import org.json.JSONObject
import java.util.UUID

data class DiaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DiaryEntry(
            id = o.getString("id"),
            title = o.optString("title"),
            content = o.optString("content"),
            createdAt = o.getLong("createdAt"),
            updatedAt = o.getLong("updatedAt")
        )
    }
}
