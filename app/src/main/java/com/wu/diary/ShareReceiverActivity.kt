package com.wu.diary

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.data.DiaryStore
import com.wu.diary.model.DiaryEntry
import java.io.File
import java.util.UUID

/**
 * 接收系统分享的文本、图片或视频：不显示界面，直接把内容保存为今日笔记，完成后 Toast 提示并退出。
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)
        val now = System.currentTimeMillis()
        Thread {
            // 复制图片、视频放到后台线程，避免分享大文件时阻塞界面。
            val (images, videos) = extractMedia(intent)
            if (text.isEmpty() && images.isEmpty() && videos.isEmpty()) {
                runOnUiThread {
                    toast(getString(R.string.share_empty))
                    finish()
                }
                return@Thread
            }
            val result = runCatching {
                DiaryStore(this).save(
                    DiaryEntry(
                        title = "",
                        content = text,
                        createdAt = now,
                        updatedAt = now,
                        images = images,
                        videos = videos
                    )
                )
            }
            runOnUiThread {
                toast(
                    getString(
                        if (result.isSuccess) R.string.share_saved else R.string.share_failed
                    )
                )
                finish()
            }
        }.start()
    }

    /** 取分享的文本，兼容浏览器等只带 URL 的场景。 */
    private fun extractText(intent: Intent?): String {
        if (intent == null) return ""
        val raw = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }
        return raw?.toString()?.trim().orEmpty()
    }

    /** 分享内容里的媒体 Uri，支持单条和多条。 */
    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE -> {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            list ?: emptyList()
        }

        Intent.ACTION_SEND -> {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            listOfNotNull(uri)
        }

        else -> emptyList()
    }

    /** 复制分享过来的图片和视频到应用私有目录，最多 9 个。 */
    private fun extractMedia(intent: Intent?): Pair<List<String>, List<String>> {
        if (intent == null) return emptyList<String>() to emptyList()
        val uris = extractUris(intent).take(9)
        if (uris.isEmpty()) return emptyList<String>() to emptyList()
        val images = mutableListOf<String>()
        val videos = mutableListOf<String>()
        // 部分应用分享视频时类型给的是 video/*，但单个 Uri 查不到 MIME，用 Intent 的类型兜底。
        val fallbackVideo = intent.type?.startsWith("video/") == true
        uris.forEach { uri ->
            val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
            val isVideo = mime?.startsWith("video/") == true ||
                (mime == null && fallbackVideo)
            runCatching {
                if (isVideo) {
                    val dir = File(filesDir, "videos").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.${videoExtension(mime)}")
                    copy(uri, target)
                    videos.add(target.absolutePath)
                } else {
                    val dir = File(filesDir, "images").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.jpg")
                    copy(uri, target)
                    images.add(target.absolutePath)
                }
            }
        }
        return images to videos
    }

    /** 视频文件后缀：优先用 MIME 的子类型，取不到就用 mp4。 */
    private fun videoExtension(mime: String?): String {
        val subtype = mime?.substringAfter("video/", "")?.substringBefore(';')?.trim()?.lowercase()
        return subtype?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp4"
    }

    private fun copy(uri: Uri, target: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
