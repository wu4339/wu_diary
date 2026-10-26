package com.wu.diary.data

import android.content.Context
import android.net.Uri
import com.wu.diary.model.DiaryEntry
import org.json.JSONArray
import java.io.File

/**
 * 日记存储入口：用户设置了自定义目录时读写该目录的 Markdown，
 * 否则使用 App 内部 JSON 文件。
 */
class DiaryStore(private val context: Context) {
    private val file = File(context.filesDir, "diaries.json")

    private val external: ExternalDiaryStore?
        get() = Settings(context).storageTreeUri?.let { raw ->
            runCatching { ExternalDiaryStore(context, Uri.parse(raw)) }.getOrNull()
        }

    fun load(): List<DiaryEntry> {
        val externalStore = external
        if (externalStore != null) {
            runCatching { externalStore.load() }.getOrNull()?.let { return it }
        }
        return loadInternal()
    }

    /** 新增或更新（按 id 覆盖），并按更新时间倒序排列。 */
    fun save(entry: DiaryEntry) {
        val externalStore = external
        if (externalStore != null) {
            runCatching { externalStore.save(entry) }.onSuccess { return }
        }
        saveInternal(entry)
    }

    fun delete(id: String) {
        val externalStore = external
        if (externalStore != null) {
            runCatching { externalStore.delete(id) }.onSuccess { return }
        }
        val all = loadInternal()
        all.find { it.id == id }?.images?.forEach { File(it).delete() }
        writeInternal(all.filterNot { it.id == id })
    }

    /** 用给定笔记整体覆盖当前存储（切换存储位置时使用）。 */
    fun replaceAll(entries: List<DiaryEntry>) {
        val externalStore = external
        if (externalStore != null) {
            runCatching { externalStore.replaceAll(entries) }.onSuccess { return }
        }
        writeInternal(entries.sortedByDescending { it.updatedAt })
    }

    private fun loadInternal(): List<DiaryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { DiaryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun saveInternal(entry: DiaryEntry) {
        val all = loadInternal().filterNot { it.id == entry.id } + entry
        writeInternal(all.sortedByDescending { it.updatedAt })
    }

    private fun writeInternal(entries: List<DiaryEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }
}
