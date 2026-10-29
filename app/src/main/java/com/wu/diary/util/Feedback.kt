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
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** 保存/删除操作提示音：直接合成 PCM 播放，音量比 ToneGenerator 更大。 */
object Feedback {

    private const val SAMPLE_RATE = 44100

    private val handler = Handler(Looper.getMainLooper())

    private val savedSamples: ShortArray by lazy { buildBubbles() }
    private val deletedSamples: ShortArray by lazy { buildRip() }
    private val pageTurnSamples: ShortArray by lazy { buildPageTurn() }

    /** 保存：水泡上浮的冒泡声。 */
    fun saved(context: Context) = play(context, savedSamples)

    /** 删除：撕纸声，干脆利落。 */
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

    /** 冒泡声：两个气泡先后上浮，音高快速上扬 + 指数衰减，像水里"噗噜"冒出。 */
    private fun buildBubbles(): ShortArray {
        val total = (SAMPLE_RATE * 0.32).toInt()
        val mix = FloatArray(total)

        // startSeconds 起播时间、fromHz→toHz 上扬音高、length 时长、gain 音量。
        fun bubble(
            startSeconds: Double,
            fromHz: Double,
            toHz: Double,
            length: Double,
            gain: Double
        ) {
            val start = (SAMPLE_RATE * startSeconds).toInt()
            val count = (SAMPLE_RATE * length).toInt()
            val grow = ln(toHz / fromHz) / length
            var phase = 0.0
            for (i in 0 until count) {
                val position = start + i
                if (position >= total) break
                val t = i.toDouble() / SAMPLE_RATE
                phase += 2 * PI * (fromHz * exp(grow * t)) / SAMPLE_RATE
                val envelope = min(1.0, t / 0.004) * exp(-8.0 * t / length)
                mix[position] += (sin(phase) * envelope * gain).toFloat()
            }
        }

        bubble(0.0, 380.0, 1250.0, 0.16, 1.0)
        bubble(0.11, 760.0, 2050.0, 0.11, 0.5)

        // 音量再放大一倍：tanh 输入继续翻倍，波形更满，软限幅防爆音。
        return ShortArray(total) { i ->
            (tanh(mix[i] * 5.0f) * 0.95f * Short.MAX_VALUE)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** 删除：撕掉一页纸的声音——高频纤维接连断裂的嘶啦声，收尾一声闷响。 */
    private fun buildRip(): ShortArray {
        val total = (SAMPLE_RATE * 0.16).toInt()
        val random = Random(7719)
        val mix = FloatArray(total)
        var low = 0f
        var lower = 0f
        for (i in 0 until total) {
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            // 撕裂噪声集中在高频，随进度略微抬高，像纸被越扯越薄。
            val cutoff = (3200.0 + 2600.0 * progress).coerceAtMost(7000.0)
            val coefficient = (2.0 * PI * cutoff / SAMPLE_RATE).toFloat().coerceIn(0.002f, 0.95f)
            val noise = random.nextFloat() * 2f - 1f
            low += coefficient * (noise - low)
            lower += coefficient * 0.75f * (low - lower)
            val band = low - lower
            // 纤维一根根崩断的颗粒感：一层快速抖动叠在噪声上。
            val fiber = 0.55f + 0.45f * sin(2.0 * PI * 42.0 * t).toFloat()
            val envelope = min(1.0, t / 0.008) * exp(-11.0 * t)
            mix[i] += (band * fiber * envelope.toFloat() * 2.6f)
        }
        // 收尾的闷响：扯下来的那一页脱手落下。
        val thumpStart = (total * 0.55).toInt()
        for (i in thumpStart until total) {
            val progress = (i - thumpStart).toDouble() / (total - thumpStart)
            val freq = 120.0 - 60.0 * progress
            val envelope = (1.0 - progress) * (1.0 - progress)
            mix[i] += (sin(2 * PI * freq * i / SAMPLE_RATE) * envelope * 1.1).toFloat()
        }
        return ShortArray(total) { i ->
            (tanh(mix[i] * 2.2f) * 0.95f * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
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
