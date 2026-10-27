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

    /** 保存 / 删除日记时是否播放提示音。 */
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SOUND, value).apply()
        }

    /** 浏览页双击是否进入编辑。 */
    var doubleTapEdit: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DOUBLE_TAP, value).apply()
        }

    /** 正文行间距，单位 dp。 */
    var lineSpacing: Float
        get() = prefs.getFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING)
        set(value) {
            prefs.edit().putFloat(KEY_LINE_SPACING, value).apply()
        }

    /** 列表预览时是否显示笔记标签。 */
    var previewTags: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_TAGS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PREVIEW_TAGS, value).apply()
        }

    /** 用户选择的笔记存储目录（SAF tree uri），为空表示使用 App 内部存储。 */
    var storageTreeUri: String?
        get() = prefs.getString(KEY_STORAGE_URI, null)
        set(value) {
            prefs.edit().putString(KEY_STORAGE_URI, value).apply()
        }

    companion object {
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_PREVIEW_LINES = "preview_lines"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_DOUBLE_TAP = "double_tap_edit"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_PREVIEW_TAGS = "preview_tags"
        private const val KEY_STORAGE_URI = "storage_tree_uri"

        val FONT_SCALES = floatArrayOf(0.8f, 0.9f, 1f, 1.15f, 1.3f)

        /** 可选的行间距（dp）。 */
        val LINE_SPACINGS = floatArrayOf(0f, 3f, 6f, 9f, 12f)

        const val DEFAULT_LINE_SPACING = 6f

        /** 默认行间距在 LINE_SPACINGS 中的下标。 */
        const val DEFAULT_LINE_SPACING_INDEX = 2

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
