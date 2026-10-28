package com.wu.diary.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.wu.diary.data.Settings
import kotlin.math.PI
import kotlin.math.exp
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
    private val deletedSamples: ShortArray by lazy { buildDrop() }
    private val pageTurnSamples: ShortArray by lazy { buildPageTurn() }

    /** 保存：两段上行音。 */
    fun saved(context: Context) = play(context, savedSamples)

    /** 删除：一声清脆的水滴音，和保存的上行音明显区分。 */
    fun deleted(context: Context) = play(context, deletedSamples)

    /** 随机回顾翻页：纸张擦过的沙沙声。 */
    fun pageTurn(context: Context) = play(context, pageTurnSamples)

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

    /** 水滴音：频率快速下坠，尾巴带一点混响感，听起来像一滴水落进水面。 */
    private fun buildDrop(): ShortArray {
        val total = (SAMPLE_RATE * 0.30).toInt()
        val out = ShortArray(total)
        var phase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            // 音高从高处快速滑到低处，模拟水滴的下坠。
            val freq = 1000.0 * exp(-10.0 * t) + 180.0
            phase += 2 * PI * freq / SAMPLE_RATE
            val attack = min(1.0, t / 0.003)
            val envelope = attack * (exp(-7.0 * t) * 0.85 + exp(-1.2 * t) * 0.15)
            val wave = sin(phase) * 0.9 + sin(2 * phase) * 0.1
            out[i] = (wave * envelope * 0.9 * Short.MAX_VALUE)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 翻书声：一团纸张摩擦的噪声，由轻到重再收尾，最后带一点页面落定的闷响。 */
    private fun buildPageTurn(): ShortArray {
        val total = (SAMPLE_RATE * 0.26).toInt()
        val random = Random(445566)
        val mix = FloatArray(total)
        var low = 0f
        var lower = 0f
        for (i in 0 until total) {
            val progress = i.toFloat() / total
            // 摩擦声的中心频率先升后降，模拟纸页划过空气。
            val cutoff = 800f + 2400f * sin(PI * progress).toFloat()
            val coefficient = (2.0 * PI * cutoff / SAMPLE_RATE).toFloat().coerceIn(0.002f, 0.95f)
            val noise = random.nextFloat() * 2f - 1f
            low += coefficient * (noise - low)
            lower += coefficient * 0.35f * (low - lower)
            val band = low - lower
            val envelope = sin(PI * progress).pow(1.1).toFloat()
            mix[i] += band * envelope * 3.0f
        }

        // 纸页落定：末尾一点低频闷响。
        val settleStart = (total * 0.72f).toInt()
        for (i in settleStart until total) {
            val progress = (i - settleStart).toFloat() / (total - settleStart)
            val freq = 150.0 - 50.0 * progress
            val envelope = (1f - progress) * (1f - progress)
            mix[i] += (sin(2 * PI * freq * i / SAMPLE_RATE) * envelope * 0.7).toFloat()
        }

        return ShortArray(total) { i ->
            val value = tanh(mix[i] * 1.4f) * 0.95f
            (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
