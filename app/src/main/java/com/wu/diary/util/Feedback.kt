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
    private val deletedSamples: ShortArray by lazy { buildCrumple() }

    /** 保存：两段上行音。 */
    fun saved(context: Context) = play(context, savedSamples)

    /** 删除：揉皱撕碎纸张的声音。 */
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

    /** 删除：揉皱/撕碎纸张的"嚓啦"声。 */
    private fun buildCrumple(): ShortArray {
        val total = (SAMPLE_RATE * 0.30).toInt()
        val mix = FloatArray(total)

        // 开头一记短促闷响，像纸团被一把攥住。
        val hit = (SAMPLE_RATE * 0.10).toInt()
        for (i in 0 until hit) {
            val ratio = i.toFloat() / hit
            val freq = 210.0 - 140.0 * ratio
            val envelope = (1f - ratio) * (1f - ratio)
            mix[i] += (sin(2 * PI * freq * i / SAMPLE_RATE) * envelope * 1.2).toFloat()
        }

        // 揉皱纸张：中频噪声被随机颗粒快速切断，形成"嚓啦嚓啦"的褶皱感。
        val random = Random(778899)
        var low = 0f
        var mid = 0f
        var grainTimer = 0
        var grainVolume = 0f
        for (i in hit until total) {
            val progress = (i - hit).toFloat() / (total - hit)
            val noise = random.nextFloat() * 2f - 1f
            low += 0.10f * (noise - low)
            mid += 0.42f * (noise - mid)
            val band = mid - low
            if (grainTimer <= 0) {
                grainTimer = 7 + (random.nextFloat() * 130f).toInt()
                grainVolume = 0.5f + random.nextFloat() * 0.9f
            }
            grainTimer--
            grainVolume *= 0.93f
            val envelope = (1f - progress) * (1f - progress)
            mix[i] += band * envelope * grainVolume * 2.2f
        }

        // 软限幅：整体更响，同时避免刺耳的破音。
        return ShortArray(total) { i ->
            val value = tanh(mix[i] * 2.0f) * 0.98f
            (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
