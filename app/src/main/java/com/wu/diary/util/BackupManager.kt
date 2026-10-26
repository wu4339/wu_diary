package com.wu.diary.util

import android.content.Context
import android.net.Uri
import com.wu.diary.data.DiaryStore
import com.wu.diary.model.DiaryEntry
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 笔记备份：导出为 zip（Diary/notes/年-月-日.md + Diary/files/图片），也可从该结构导入。
 */
object BackupManager {

    private const val DIARY_DIR = "Diary"
    private const val NOTE_DIR = "Diary/notes"
    private const val IMAGE_DIR = "Diary/files"
    /** md 位于 Diary/notes，图片相对引用到 Diary/files。 */
    private const val IMAGE_REF = "../files/"

    /** 导出全部笔记到指定 uri，返回导出的笔记数量。 */
    fun export(context: Context, uri: Uri): Int {
        val entries = DiaryStore(context).load().sortedBy { it.createdAt }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法写入导出文件")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                val copiedImages = HashSet<String>()
                entries.groupBy { dayFormat.format(Date(it.createdAt)) }
                    .forEach { (day, dayEntries) ->
                        val markdown = buildMarkdown(day, dayEntries, timeFormat)
                        zip.putNextEntry(ZipEntry("$NOTE_DIR/$day.md"))
                        zip.write(markdown.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        dayEntries.forEach { entry ->
                            entry.images.forEach { path ->
                                val image = File(path)
                                if (image.exists() && copiedImages.add(image.name)) {
                                    zip.putNextEntry(ZipEntry("$IMAGE_DIR/${image.name}"))
                                    image.inputStream().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
            }
        }
        return entries.size
    }

    /** 从 zip 导入笔记，返回新增的笔记数量。 */
    fun import(context: Context, uri: Uri): Int {
        val store = DiaryStore(context)
        val imageDir = File(context.filesDir, "images").apply { mkdirs() }
        val existingKeys = store.load()
            .map { "${it.createdAt}|${it.content}" }
            .toMutableSet()
        val importedImages = mutableMapOf<String, String>()
        val markdowns = mutableListOf<Pair<String, String>>()

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取导入文件")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var zipEntry = zip.nextEntry
                while (zipEntry != null) {
                    val name = zipEntry.name.replace('\\', '/')
                    if (!zipEntry.isDirectory) {
                        when {
                            name.startsWith("$DIARY_DIR/") && name.endsWith(".md", true) -> {
                                markdowns.add(
                                    name.substringAfterLast('/') to
                                        String(zip.readBytes(), Charsets.UTF_8)
                                )
                            }
                            name.startsWith("$IMAGE_DIR/") || name.startsWith("file/images/") -> {
                                val baseName = name.substringAfterLast('/')
                                if (baseName.isNotEmpty()) {
                                    val target = uniqueImage(imageDir, baseName)
                                    target.outputStream().use { zip.copyTo(it) }
                                    importedImages[baseName] = target.absolutePath
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    zipEntry = zip.nextEntry
                }
            }
        }

        var count = 0
        markdowns.forEach { (fileName, markdown) ->
            val day = Regex("\\d{4}-\\d{2}-\\d{2}").find(fileName)?.value ?: return@forEach
            val dayStart = dayStartMillis(day) ?: return@forEach
            parseNotes(dayStart, markdown).forEach { note ->
                val entry = DiaryEntry(
                    title = "",
                    content = note.content,
                    createdAt = note.createdAt,
                    updatedAt = note.createdAt,
                    images = note.images.mapNotNull { importedImages[it] },
                    tags = note.tags,
                    hidden = note.hidden
                )
                if (existingKeys.add("${entry.createdAt}|${entry.content}")) {
                    store.save(entry)
                    count++
                }
            }
        }
        return count
    }

    /** 生成某天笔记的 markdown（图片相对引用 Diary/files）。 */
    fun markdownFor(day: String, entries: List<DiaryEntry>): String =
        buildMarkdown(day, entries, SimpleDateFormat("HH:mm:ss", Locale.US))

    /** 解析某天的 markdown；图片字段为文件名，由调用方到图片目录解析。 */
    fun parse(day: String, markdown: String): List<DiaryEntry> {
        val dayStart = dayStartMillis(day) ?: return emptyList()
        return parseNotes(dayStart, markdown).map { note ->
            DiaryEntry(
                title = "",
                content = note.content,
                createdAt = note.createdAt,
                updatedAt = note.createdAt,
                images = note.images,
                tags = note.tags,
                hidden = note.hidden
            )
        }
    }

    private fun buildMarkdown(
        day: String,
        entries: List<DiaryEntry>,
        timeFormat: SimpleDateFormat
    ): String = buildString {
        append("# ").append(day).append("\n\n")
        entries.forEachIndexed { index, entry ->
            append("## ").append(timeFormat.format(Date(entry.createdAt)))
            if (entry.hidden) append(" 隐藏")
            append("\n\n")
            val body = listOf(entry.title, entry.content)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            if (body.isNotEmpty()) append(body).append("\n\n")
            if (entry.tags.isNotEmpty()) {
                append("标签：")
                append(entry.tags.joinToString(" ") { "#$it" })
                append("\n\n")
            }
            entry.images.forEach { path ->
                append("![]($IMAGE_REF")
                append(File(path).name).append(")\n")
            }
            if (entry.images.isNotEmpty()) append("\n")
            if (index != entries.lastIndex) append("\n")
        }
    }

    private data class ParsedNote(
        val createdAt: Long,
        val content: String,
        val tags: List<String>,
        val images: List<String>,
        val hidden: Boolean
    )

    private fun parseNotes(dayStart: Long, markdown: String): List<ParsedNote> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val sections = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        lines.forEach { line ->
            if (line.startsWith("## ")) {
                val section = mutableListOf(line.removePrefix("## "))
                sections.add(section)
                current = section
            } else if (current != null) {
                current!!.add(line)
            }
        }
        // 没有二级标题时，整个文件视为一篇笔记。
        if (sections.isEmpty()) sections.add(mutableListOf("").apply { addAll(lines) })
        return sections.mapNotNull { parseSection(dayStart, it) }
    }

    private fun parseSection(dayStart: Long, section: List<String>): ParsedNote? {
        if (section.isEmpty()) return null
        val header = section.first().trim()
        val time = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").find(header)?.let { match ->
            val hours = match.groupValues[1].toInt()
            val minutes = match.groupValues[2].toInt()
            val seconds = match.groupValues[3].ifEmpty { "0" }.toInt()
            hours * 3600 + minutes * 60 + seconds
        } ?: 0
        val hidden = header.contains("隐藏")

        val bodyLines = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val images = mutableListOf<String>()
        section.drop(1).forEach { line ->
            val trimmed = line.trim()
            val image = Regex("!\\[[^\\]]*]\\(([^)]+)\\)").find(trimmed)
            if (image != null) {
                val path = image.groupValues[1]
                if (path.contains("/images/") || path.contains("/files/")) {
                    images.add(path.substringAfterLast('/'))
                }
                return@forEach
            }
            val tagText = when {
                trimmed.startsWith("标签：") -> trimmed.removePrefix("标签：")
                trimmed.startsWith("标签:") -> trimmed.removePrefix("标签:")
                else -> null
            }
            if (tagText != null) {
                tags.addAll(
                    tagText.split(Regex("[\\s,，、]+"))
                        .map { it.removePrefix("#").trim() }
                        .filter { it.isNotEmpty() }
                )
                return@forEach
            }
            bodyLines.add(line)
        }

        val content = bodyLines.joinToString("\n").trim()
        if (content.isEmpty() && images.isEmpty()) return null
        return ParsedNote(
            createdAt = dayStart + time * 1000L,
            content = content,
            tags = tags.distinct(),
            images = images,
            hidden = hidden
        )
    }

    private fun dayStartMillis(day: String): Long? {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) ?: return null
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** 同名图片已存在时自动追加序号，避免覆盖。 */
    private fun uniqueImage(dir: File, name: String): File {
        val base = File(name).nameWithoutExtension.ifEmpty { "image" }
        val extension = File(name).extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        var target = File(dir, base + extension)
        var index = 1
        while (target.exists()) {
            target = File(dir, "$base-$index$extension")
            index++
        }
        return target
    }
}
