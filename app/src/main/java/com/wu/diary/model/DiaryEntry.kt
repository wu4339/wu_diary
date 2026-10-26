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
    val images: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("images", JSONArray(images))
    }

    companion object {
        fun fromJson(o: JSONObject): DiaryEntry {
            val imageArray = o.optJSONArray("images")
            val images = buildList {
                if (imageArray != null) {
                    for (i in 0 until imageArray.length()) add(imageArray.optString(i))
                }
            }
            return DiaryEntry(
                id = o.getString("id"),
                title = o.optString("title"),
                content = o.optString("content"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt"),
                images = images
            )
        }
    }
}
