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
    private val deletedSamples: ShortArray by lazy { buildTrashLid() }
    private val pageTurnSamples: ShortArray by lazy { buildPageTurn() }

    /** 保存：两段上行音。 */
    fun saved(context: Context) = play(context, savedSamples)

    /** 删除：垃圾桶盖合上的"咔哒"声，一声干脆的合盖。 */
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

    /** 垃圾桶盖合上声：短促的"咔"（高频敲击）+ 干脆的"哒"（中频快速下落），像盖子扣紧。 */
    private fun buildTrashLid(): ShortArray {
        val total = (SAMPLE_RATE * 0.22).toInt()
        val random = Random(112233)
        val mix = FloatArray(total)

        fun click(start: Int, freq: Double, strength: Double, decay: Double) {
            // 敲击瞬间的短噪声，让声音有"硬物相碰"的质感。
            for (i in 0 until (SAMPLE_RATE * 0.006).toInt()) {
                val pos = start + i
                if (pos >= total) break
                val envelope = 1.0 - i / (SAMPLE_RATE * 0.006).toDouble()
                mix[pos] += ((random.nextDouble() * 2 - 1) * envelope * strength * 0.5).toFloat()
            }
            // 阻尼振荡：决定音色主体。
            var phase = 0.0
            for (i in 0 until (SAMPLE_RATE * 0.09).toInt()) {
                val pos = start + i
                if (pos >= total) break
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * freq / SAMPLE_RATE
                val envelope = exp(-decay * t)
                mix[pos] += (sin(phase) * envelope * strength).toFloat()
            }
        }

        // 第一声"咔"：清脆高频。
        click(0, 950.0, 0.55, 60.0)
        // 第二声"哒"：合盖落定的中低频主体，稍强。
        click((SAMPLE_RATE * 0.045).toInt(), 430.0, 0.85, 34.0)
        // 尾音：盖子扣紧的轻微回响。
        click((SAMPLE_RATE * 0.115).toInt(), 680.0, 0.28, 55.0)

        return ShortArray(total) { i ->
            (tanh(mix[i] * 1.7f) * 0.95f * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
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
