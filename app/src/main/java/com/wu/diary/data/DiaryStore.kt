package com.wu.diary.data

import android.content.Context
import com.wu.diary.model.DiaryEntry
import org.json.JSONArray
import java.io.File

/** 基于 JSON 文件的本地日记存储。 */
class DiaryStore(context: Context) {
    private val file = File(context.filesDir, "diaries.json")

    fun load(): List<DiaryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { DiaryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    /** 新增或更新（按 id 覆盖），并按更新时间倒序排列。 */
    fun save(entry: DiaryEntry) {
        val all = load().filterNot { it.id == entry.id } + entry
        write(all.sortedByDescending { it.updatedAt })
    }

    fun delete(id: String) {
        write(load().filterNot { it.id == id })
    }

    private fun write(entries: List<DiaryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }
}
