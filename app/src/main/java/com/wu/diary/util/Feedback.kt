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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** 保存/删除操作提示音：直接合成 PCM 播放，音量比 ToneGenerator 更大。 */
object Feedback {

    private const val SAMPLE_RATE = 44100
    private const val NOTE_SECONDS = 0.14

    private val handler = Handler(Looper.getMainLooper())

    private val savedSamples: ShortArray by lazy { buildNotes(doubleArrayOf(880.0, 1318.5)) }
    private val deletedSamples: ShortArray by lazy { buildRecycle() }

    /** 保存：两段上行音。 */
    fun saved(context: Context) = play(context, savedSamples)

    /** 删除：类似 Windows 清空回收站的下坠气声。 */
    fun deleted(context: Context) = play(context, deletedSamples)

    /** 合成与播放放到后台线程，避免点击保存/删除时阻塞界面。 */
    private fun play(context: Context, samples: ShortArray) {
        if (!Settings(context).soundEnabled) return
        Thread {
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
        }.start()
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

    /** 删除：模拟 Windows「清空回收站」的短促下坠气声，结尾一记闷响。 */
    private fun buildRecycle(): ShortArray {
        val total = (SAMPLE_RATE * 0.60).toInt()
        val mix = FloatArray(total)
        val random = Random(20261026)

        // 噪声经截止频率快速下滑的低通，形成「嗖」的一口气被抽走的感觉。
        var low = 0f
        var lower = 0f
        for (i in 0 until total) {
            val progress = i.toFloat() / total
            val cutoff = 3400f * (1f - progress).pow(2.4f) + 380f
            val coefficient = (2.0 * PI * cutoff / SAMPLE_RATE).toFloat().coerceIn(0.002f, 0.92f)
            val noise = random.nextFloat() * 2f - 1f
            low += coefficient * (noise - low)
            lower += coefficient * 0.45f * (low - lower)
            val band = low - lower
            val attack = min(1.0, i / (SAMPLE_RATE * 0.02)).toFloat()
            val envelope = attack * (1f - progress).pow(1.5f)
            mix[i] += band * envelope * 3.2f
        }

        // 结尾一记低沉的「咚」，像垃圾桶盖合上。
        val plopStart = (total * 0.72f).toInt()
        for (i in plopStart until total) {
            val progress = (i - plopStart).toFloat() / (total - plopStart)
            val freq = 200.0 - 110.0 * progress
            val envelope = (1f - progress) * (1f - progress)
            mix[i] += (sin(2 * PI * freq * i / SAMPLE_RATE) * envelope * 1.1).toFloat()
        }

        // 软限幅，整体接近满幅但不会破音。
        return ShortArray(total) { i ->
            val value = tanh(mix[i] * 1.7f) * 0.98f
            (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
