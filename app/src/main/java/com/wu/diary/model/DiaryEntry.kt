package com.wu.diary.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DiaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 本地图片绝对路径列表。 */
    val images: List<String> = emptyList(),
    /** 笔记标签。 */
    val tags: List<String> = emptyList(),
    /** 是否已隐藏（只在隐藏空间显示）。 */
    val hidden: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("images", JSONArray(images))
        put("tags", JSONArray(tags))
        put("hidden", hidden)
    }

    companion object {
        fun fromJson(o: JSONObject): DiaryEntry {
            return DiaryEntry(
                id = o.getString("id"),
                title = o.optString("title"),
                content = o.optString("content"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                images = readStringList(o.optJSONArray("images")),
                tags = readStringList(o.optJSONArray("tags")),
                hidden = o.optBoolean("hidden", false)
            )
        }

        private fun readStringList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) add(array.optString(i))
            }
        }
    }
}
