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
import kotlin.math.tanh
import kotlin.random.Random

/** 保存/删除操作提示音：直接合成 PCM 播放，音量比 ToneGenerator 更大。 */
object Feedback {

    private const val SAMPLE_RATE = 44100
    private const val NOTE_SECONDS = 0.14

    private val handler = Handler(Looper.getMainLooper())

    private val savedSamples: ShortArray by lazy { buildNotes(doubleArrayOf(880.0, 1318.5)) }
    private val deletedSamples: ShortArray by lazy { buildShred() }

    /** 保存：两段上行音。 */
    fun saved(context: Context) = play(context, savedSamples)

    /** 删除：纸张被撕碎粉碎的声音。 */
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

    /** 粉碎声：低频闷响 + 密集颗粒噪声，模拟纸张被撕碎粉碎。 */
    private fun buildShred(): ShortArray {
        val total = (SAMPLE_RATE * 0.38).toInt()
        val mix = FloatArray(total)

        // 起始的低频闷响，给"砸下去"的重量感。
        val thump = (SAMPLE_RATE * 0.12).toInt()
        for (i in 0 until thump) {
            val ratio = i.toFloat() / thump
            val freq = 160.0 - 100.0 * ratio
            val envelope = (1f - ratio) * (1f - ratio)
            mix[i] += (sin(2 * PI * freq * i / SAMPLE_RATE) * envelope * 1.1).toFloat()
        }

        // 密集颗粒噪声：随机脉冲经高通后形成连续的撕碎颗粒感。
        val random = Random(20261026)
        var highPass = 0f
        var previous = 0f
        var grainTimer = 0
        var grainVolume = 0f
        for (i in thump until total) {
            val progress = (i - thump).toFloat() / (total - thump)
            val overall = (1f - progress) * (1f - progress)
            val noise = random.nextFloat() * 2f - 1f
            highPass = 0.86f * (highPass + noise - previous)
            previous = noise
            if (grainTimer <= 0) {
                grainTimer = 9 + (random.nextFloat() * 220f).toInt()
                grainVolume = 0.6f + random.nextFloat() * 0.7f
            }
            grainTimer--
            grainVolume *= 0.9f
            mix[i] += highPass * overall * grainVolume * 1.8f
        }

        // 软限幅：整体更响，同时避免刺耳的破音。
        return ShortArray(total) { i ->
            val value = tanh(mix[i] * 2.0f) * 0.98f
            (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
