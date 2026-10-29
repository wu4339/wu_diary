package com.wu.diary.data

import android.content.Context
import android.graphics.Typeface

/** 本地偏好设置：正文字体、字号、列表预览最大行数。 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 正文使用的系统字体家族，如 sans-serif / serif / monospace。 */
    var fontFamily: String
        get() {
            val stored = prefs.getString(KEY_FONT_FAMILY, DEFAULT_FONT_FAMILY)
            return if (stored != null && stored in FONT_FAMILIES) stored else DEFAULT_FONT_FAMILY
        }
        set(value) {
            prefs.edit().putString(KEY_FONT_FAMILY, value).apply()
        }

    /** 当前字体对应的 Typeface，正文和预览共用。 */
    val typeface: Typeface
        get() = Typeface.create(fontFamily, Typeface.NORMAL)

    /** 列表预览正文字号，单位 sp。 */
    var fontSp: Int
        get() = prefs.getInt(KEY_FONT_SP, DEFAULT_FONT_SP).coerceIn(FONT_SP_MIN, FONT_SP_MAX)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SP, value.coerceIn(FONT_SP_MIN, FONT_SP_MAX)).apply()
        }

    /** 查看 / 编辑页面的正文字号，单位 sp，与列表预览分开设置。 */
    var readingFontSp: Int
        get() = prefs.getInt(KEY_READING_FONT_SP, DEFAULT_READING_FONT_SP)
            .coerceIn(FONT_SP_MIN, FONT_SP_MAX)
        set(value) {
            prefs.edit().putInt(KEY_READING_FONT_SP, value.coerceIn(FONT_SP_MIN, FONT_SP_MAX)).apply()
        }

    /** 列表预览的字号缩放系数（相对 16sp 基准），日期等其它文字按同比例缩放。 */
    val previewScale: Float
        get() = fontSp / PREVIEW_BASE_SP

    /** 查看 / 编辑页的字号缩放系数（相对 17sp 基准）。 */
    val readingScale: Float
        get() = readingFontSp / READING_BASE_SP

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

    /** 查看 / 编辑页面的行间距，单位 dp，与列表预览分开设置。 */
    var readingLineSpacing: Float
        get() = prefs.getFloat(KEY_READING_LINE_SPACING, DEFAULT_LINE_SPACING)
        set(value) {
            prefs.edit().putFloat(KEY_READING_LINE_SPACING, value).apply()
        }

    /** 列表预览时是否显示笔记标签。 */
    var previewTags: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_TAGS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PREVIEW_TAGS, value).apply()
        }

    /** 图片显示方式：IMAGE_SMALL 小图、IMAGE_MEDIUM 中图、IMAGE_LARGE 大图。 */
    var imageStyle: Int
        get() {
            if (prefs.contains(KEY_IMAGE_STYLE)) {
                return prefs.getInt(KEY_IMAGE_STYLE, IMAGE_SMALL).coerceIn(IMAGE_SMALL, IMAGE_LARGE)
            }
            // 兼容更早的版本：当时只区分大图 / 小图。
            val legacy = if (prefs.getBoolean(KEY_LARGE_IMAGES, false)) IMAGE_LARGE else IMAGE_SMALL
            prefs.edit().putInt(KEY_IMAGE_STYLE, legacy).apply()
            return legacy
        }
        set(value) {
            prefs.edit().putInt(KEY_IMAGE_STYLE, value.coerceIn(IMAGE_SMALL, IMAGE_LARGE)).apply()
        }

    /** 大图模式：图片全宽铺满。 */
    val largeImages: Boolean
        get() = imageStyle == IMAGE_LARGE

    /** 中图模式：固定比例的圆角卡片。 */
    val mediumImages: Boolean
        get() = imageStyle == IMAGE_MEDIUM

    /** 用户选择的笔记存储目录（SAF tree uri），为空表示使用 App 内部存储。 */
    var storageTreeUri: String?
        get() = prefs.getString(KEY_STORAGE_URI, null)
        set(value) {
            prefs.edit().putString(KEY_STORAGE_URI, value).apply()
        }

    companion object {
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_SP = "font_sp"
        private const val KEY_READING_FONT_SP = "reading_font_sp"
        private const val KEY_PREVIEW_LINES = "preview_lines"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_DOUBLE_TAP = "double_tap_edit"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_READING_LINE_SPACING = "reading_line_spacing"
        private const val KEY_PREVIEW_TAGS = "preview_tags"
        private const val KEY_LARGE_IMAGES = "large_images"
        private const val KEY_IMAGE_STYLE = "image_style"
        private const val KEY_STORAGE_URI = "storage_tree_uri"

        /** 列表预览正文的基准字号（sp），其它文字按比例缩放。 */
        private const val PREVIEW_BASE_SP = 16f

        /** 查看 / 编辑页正文的基准字号（sp）。 */
        private const val READING_BASE_SP = 17f

        /** 图片显示方式：小图（横向缩略图）、中图（固定比例卡片）、大图（全宽）。 */
        const val IMAGE_SMALL = 0
        const val IMAGE_MEDIUM = 1
        const val IMAGE_LARGE = 2

        /** 可选字体：系统默认（无衬线）、衬线、等宽、圆润。 */
        val FONT_FAMILIES = arrayOf("sans-serif", "serif", "monospace", "casual")
        const val DEFAULT_FONT_FAMILY = "sans-serif"

        /** 可选正文字号（sp），12sp ~ 22sp，精度 1sp。 */
        const val FONT_SP_MIN = 12
        const val FONT_SP_MAX = 22
        val FONT_SPS = (FONT_SP_MIN..FONT_SP_MAX).toList()

        const val DEFAULT_FONT_SP = 16
        const val DEFAULT_READING_FONT_SP = 17

        /** 可选的行间距（dp），0dp ~ 12dp，精度 1dp。 */
        val LINE_SPACINGS = (0..12).map { it.toFloat() }

        const val DEFAULT_LINE_SPACING = 6f

        /** 默认行间距在 LINE_SPACINGS 中的下标。 */
        const val DEFAULT_LINE_SPACING_INDEX = 6

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
