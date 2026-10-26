package com.wu.diary

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.data.DiaryStore
import com.wu.diary.model.DiaryEntry

/**
 * 接收系统分享的文本：不显示界面，直接把文本保存为今日笔记，完成后 Toast 提示并退出。
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = extractText(intent)
        if (text.isEmpty()) {
            toast(getString(R.string.share_empty))
            finish()
            return
        }
        val now = System.currentTimeMillis()
        Thread {
            val result = runCatching {
                DiaryStore(this).save(
                    DiaryEntry(title = "", content = text, createdAt = now, updatedAt = now)
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

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
