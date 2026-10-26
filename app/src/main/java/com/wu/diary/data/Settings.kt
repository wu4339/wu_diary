package com.wu.diary.data

import android.content.Context

/** 本地偏好设置：正文字号缩放、列表预览最大行数。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()
        }

    var previewLines: Int
        get() = prefs.getInt(KEY_PREVIEW_LINES, 4)
        set(value) {
            prefs.edit().putInt(KEY_PREVIEW_LINES, value).apply()
        }

    companion object {
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_PREVIEW_LINES = "preview_lines"

        val FONT_SCALES = floatArrayOf(0.8f, 0.9f, 1f, 1.15f, 1.3f)

        /** 默认字号档位在 FONT_SCALES 中的下标。 */
        const val DEFAULT_FONT_INDEX = 2

        val PREVIEW_LINES = intArrayOf(1, 2, 3, 4, 5, 6)

        /** 默认预览行数在 PREVIEW_LINES 中的下标。 */
        const val DEFAULT_PREVIEW_INDEX = 3
    }
}
