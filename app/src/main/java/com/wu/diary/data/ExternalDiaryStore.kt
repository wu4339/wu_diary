package com.wu.diary.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wu.diary.model.DiaryEntry
import com.wu.diary.util.BackupManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户自定义目录存储：笔记写在 Diary/notes/年-月-日.md，图片写在 Diary/files。
 * 通过 SAF 访问，图片加载时复制一份到 App 私有缓存供显示。
 */
class ExternalDiaryStore(private val context: Context, private val treeUri: Uri) {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    init {
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) throw SecurityException("没有该目录的读写权限")
    }

    private val diaryDir: DocumentFile? by lazy { ensureDir(DocumentFile.fromTreeUri(context, treeUri), "Diary") }
    private val notesDir: DocumentFile? by lazy { ensureDir(diaryDir, "notes") }
    private val imageDir: DocumentFile? by lazy { ensureDir(diaryDir, "files") }

    /** 目录里是否已经有 markdown 笔记。 */
    fun hasNotes(): Boolean = markdownFiles().isNotEmpty()

    fun load(): List<DiaryEntry> {
        val images = imageNames()
        return markdownFiles().sortedBy { it.name }.flatMap { doc ->
            val day = dayOf(doc.name.orEmpty()) ?: return@flatMap emptyList()
            readEntries(day, doc, images)
        }
    }

    /** 只重写笔记所属的那一天，避免每次保存都读写全部文件。 */
    fun save(entry: DiaryEntry) {
        val day = dayFormat.format(Date(entry.createdAt))
        copyImages(entry.images)
        writeDay(day, loadDay(day).filterNot { it.id == entry.id } + entry)
    }

    fun delete(id: String) {
        val day = dayFromId(id)
            ?: load().find { it.id == id }?.let { dayFormat.format(Date(it.createdAt)) }
            ?: return
        writeDay(day, loadDay(day).filterNot { it.id == id })
    }

    /** 用给定笔记覆盖整个目录（用于切换存储位置时迁移）。 */
    fun replaceAll(entries: List<DiaryEntry>) {
        copyImages(entries.flatMap { it.images })
        val grouped = entries.groupBy { dayFormat.format(Date(it.createdAt)) }
        grouped.forEach { (day, dayEntries) -> writeDay(day, dayEntries) }
        markdownFiles().mapNotNull { it.name }
            .filterNot { it in grouped.keys.map { day -> "$day.md" } }
            .forEach { notesDir?.findFile(it)?.delete() }
    }

    private fun loadDay(day: String): List<DiaryEntry> {
        val doc = notesDir?.findFile("$day.md") ?: return emptyList()
        return readEntries(day, doc, imageNames())
    }

    private fun readEntries(day: String, doc: DocumentFile, images: Set<String>): List<DiaryEntry> {
        val markdown = readText(doc) ?: return emptyList()
        return BackupManager.parse(day, markdown).mapIndexed { index, entry ->
            entry.copy(
                id = "ext-$day-${entry.createdAt}-$index",
                images = entry.images.filter { it in images }.mapNotNull { cacheImage(it) }
            )
        }
    }

    private fun writeDay(day: String, entries: List<DiaryEntry>) {
        val name = "$day.md"
        val existing = notesDir?.findFile(name)
        if (entries.isEmpty()) {
            existing?.delete()
            return
        }
        val content = BackupManager.markdownFor(day, entries.sortedBy { e -> e.createdAt })
            .toByteArray(Charsets.UTF_8)
        // 优先覆盖已有文件，避免删除后再重建的瞬间文件不存在，导致界面读到空的一天。
        if (existing != null) {
            val overwritten = runCatching {
                context.contentResolver.openOutputStream(existing.uri, "wt")?.use {
                    it.write(content)
                } ?: error("无法写入笔记文件")
            }.isSuccess
            if (overwritten) return
            existing.delete()
        }
        val file = notesDir?.createFile("text/markdown", name) ?: error("无法创建笔记文件")
        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(content)
        } ?: error("无法写入笔记文件")
    }

    private fun copyImages(paths: List<String>) {
        val copied = HashSet<String>()
        paths.forEach { path ->
            val image = File(path)
            if (image.exists() && copied.add(image.name)) copyImageToDir(image)
        }
    }

    private fun copyImageToDir(image: File) {
        val dir = imageDir ?: return
        if (dir.findFile(image.name) != null) return
        val mime = when (image.extension.lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
        val target = dir.createFile(mime, image.name) ?: return
        runCatching {
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                image.inputStream().use { input -> input.copyTo(output) }
            }
        }.onFailure { target.delete() }
    }

    /** 外部图片复制到内部缓存后返回本地路径，供 ImageLoader 解码。 */
    private fun cacheImage(name: String): String? {
        val cacheDir = File(context.filesDir, "external_images").apply { mkdirs() }
        val cached = File(cacheDir, name)
        if (cached.exists()) return cached.absolutePath
        val source = imageDir?.findFile(name) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
            cached.absolutePath
        }.getOrNull()
    }

    private fun imageNames(): Set<String> =
        imageDir?.listFiles()?.mapNotNull { it.name }.orEmpty().toSet()

    private fun markdownFiles(): List<DocumentFile> =
        notesDir?.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".md", true) == true }
            .orEmpty()

    private fun dayOf(name: String): String? =
        Regex("\\d{4}-\\d{2}-\\d{2}").find(name)?.value

    private fun dayFromId(id: String): String? =
        Regex("^ext-(\\d{4}-\\d{2}-\\d{2})-").find(id)?.groupValues?.get(1)

    private fun readText(doc: DocumentFile): String? = runCatching {
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private fun ensureDir(parent: DocumentFile?, name: String): DocumentFile? {
        if (parent == null) return null
        parent.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        return parent.createDirectory(name)
    }
}
