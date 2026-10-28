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
 * 接收系统分享的文本或图片：不显示界面，直接把内容保存为今日笔记，完成后 Toast 提示并退出。
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)
        val now = System.currentTimeMillis()
        Thread {
            // 复制图片放到后台线程，避免分享大图时阻塞界面。
            val images = extractImages(intent)
            if (text.isEmpty() && images.isEmpty()) {
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
                        images = images
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

    /** 复制分享过来的图片到应用私有目录，最多 9 张。 */
    private fun extractImages(intent: Intent?): List<String> {
        if (intent == null) return emptyList()
        val uris: List<Uri> = when (intent.action) {
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
        if (uris.isEmpty()) return emptyList()
        val dir = File(filesDir, "images").apply { mkdirs() }
        return uris.take(9).mapNotNull { uri ->
            runCatching {
                val target = File(dir, "${UUID.randomUUID()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.absolutePath
            }.getOrNull()
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
