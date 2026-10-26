package com.wu.diary.data

import android.content.Context

/** 本地偏好设置：目前只有正文字号缩放。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()
        }

    companion object {
        private const val KEY_FONT_SCALE = "font_scale"
        val FONT_SCALES = floatArrayOf(0.9f, 1f, 1.15f, 1.3f)
    }
}
