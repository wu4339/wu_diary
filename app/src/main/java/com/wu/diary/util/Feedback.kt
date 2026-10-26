package com.wu.diary.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.wu.diary.data.Settings
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 保存/删除操作提示音：直接合成 PCM 播放，音量比 ToneGenerator 更大。 */
object Feedback {

    private const val SAMPLE_RATE = 44100
    private const val NOTE_SECONDS = 0.14

    private val handler = Handler(Looper.getMainLooper())

    /** 保存：两段上行音。 */
    fun saved(context: Context) = play(context, buildNotes(doubleArrayOf(880.0, 1318.5)))

    /** 删除：捏碎/粉碎的噪声声。 */
    fun deleted(context: Context) = play(context, buildCrunch())

    private fun play(context: Context, samples: ShortArray) {
        if (!Settings(context).soundEnabled) return
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.setVolume(1f)
            track.write(samples, 0, samples.size)
            track.play()
            handler.postDelayed({
                runCatching { track.release() }
            }, samples.size * 1000L / SAMPLE_RATE + 200L)
        }
    }

    private fun buildNotes(notes: DoubleArray): ShortArray {
        val noteSamples = (SAMPLE_RATE * NOTE_SECONDS).toInt()
        val out = ShortArray(noteSamples * notes.size)
        var offset = 0
        for (freq in notes) {
            for (i in 0 until noteSamples) {
                val fadeIn = min(1.0, i / (SAMPLE_RATE * 0.01))
                val fadeOut = min(1.0, (noteSamples - i) / (SAMPLE_RATE * 0.04))
                val envelope = min(fadeIn, fadeOut)
                val wave = sin(2 * PI * freq * i / SAMPLE_RATE)
                out[offset + i] = (wave * envelope * 0.95 * Short.MAX_VALUE).toInt().toShort()
            }
            offset += noteSamples
        }
        return out
    }

    /** 三段短促噪声脉冲，带高频颗粒感，听感接近纸张被捏碎。 */
    private fun buildCrunch(): ShortArray {
        val total = (SAMPLE_RATE * 0.24).toInt()
        val out = ShortArray(total)
        val random = Random(20261026)
        val bursts = listOf(0.0 to 0.055, 0.07 to 0.05, 0.135 to 0.085)
        for ((start, duration) in bursts) {
            val from = (SAMPLE_RATE * start).toInt().coerceIn(0, total - 1)
            val length = (SAMPLE_RATE * duration).toInt().coerceAtMost(total - from)
            var lowPass = 0f
            for (i in 0 until length) {
                val progress = i.toFloat() / length
                val envelope = (1f - progress) * (1f - progress)
                val noise = random.nextFloat() * 2f - 1f
                lowPass += 0.6f * (noise - lowPass)
                val crisp = noise - lowPass
                val value = crisp * envelope * 0.95f
                out[from + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
