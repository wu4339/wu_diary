package com.wu.diary.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.wu.diary.data.Settings

/** 操作反馈音：保存、删除时短促提示一声。 */
object Feedback {

    private var tone: ToneGenerator? = null

    fun beep(context: Context) {
        if (!Settings(context).soundEnabled) return
        val generator = tone ?: runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        }.getOrNull() ?: return
        tone = generator
        runCatching { generator.startTone(ToneGenerator.TONE_PROP_ACK, 160) }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { generator.stopTone() }
        }, 260)
    }
}
