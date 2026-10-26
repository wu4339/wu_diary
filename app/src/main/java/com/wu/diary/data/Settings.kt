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
        get() {
            val stored = prefs.getInt(KEY_PREVIEW_LINES, DEFAULT_PREVIEW_LINES)
            val valid = stored == PREVIEW_ALL || stored in PREVIEW_MIN..PREVIEW_MAX
            return if (valid) stored else DEFAULT_PREVIEW_LINES
        }
        set(value) {
            prefs.edit().putInt(KEY_PREVIEW_LINES, value).apply()
        }

    companion object {
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_PREVIEW_LINES = "preview_lines"

        val FONT_SCALES = floatArrayOf(0.8f, 0.9f, 1f, 1.15f, 1.3f)

        /** 默认字号档位在 FONT_SCALES 中的下标。 */
        const val DEFAULT_FONT_INDEX = 2

        /** 可选的预览行数（不含「全显」）。 */
        val PREVIEW_LINES = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10)

        /** 预览行数取该值时表示全部显示。 */
        const val PREVIEW_ALL = 0

        const val PREVIEW_MIN = 3
        const val PREVIEW_MAX = 10

        const val DEFAULT_PREVIEW_LINES = 4

        /** 默认预览行数在选项中的下标。 */
        const val DEFAULT_PREVIEW_INDEX = 1
    }
}
