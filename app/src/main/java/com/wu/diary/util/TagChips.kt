package com.wu.diary.util

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 标签色块：仿 Obsidian 的标签样式——淡紫色圆角色块，周围带一层柔和的同色光晕。
 */
object TagChips {

    /** 标签主色（淡紫）。 */
    private const val ACCENT = 0xFF7C6CE6.toInt()

    /** 在容器里重绘全部标签色块；tile=true 时用和定位/标签图标一致的小灰矩形样式。 */
    fun render(
        container: LinearLayout,
        tags: List<String>,
        scale: Float,
        onClick: ((String) -> Unit)? = null,
        tile: Boolean = false
    ) {
        container.removeAllViews()
        tags.forEach { tag ->
            container.addView(
                if (tile) createTileChip(container, tag, scale, onClick)
                else createChip(container, tag, scale, onClick)
            )
        }
    }

    /** 笔记头部的小灰矩形标签，高度与定位/标签图标一致（18dp），四角 4dp。 */
    private fun createTileChip(
        container: LinearLayout,
        tag: String,
        scale: Float,
        onClick: ((String) -> Unit)?
    ): TextView {
        val density = container.resources.displayMetrics.density
        return TextView(container.context).apply {
            text = "#$tag"
            gravity = Gravity.CENTER
            setTextColor(0xFF565656.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            includeFontPadding = false
            minimumHeight = (18 * density).toInt()
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * density
                setColor(0xFFF2F2F2.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
            }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick(tag) }
            }
        }
    }

    private fun createChip(
        container: LinearLayout,
        tag: String,
        scale: Float,
        onClick: ((String) -> Unit)?
    ): TextView {
        val density = container.resources.displayMetrics.density
        return TextView(container.context).apply {
            text = "#$tag"
            gravity = Gravity.CENTER
            setTextColor(0xFF5B4BC4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            setPadding(
                (7 * density).toInt(),
                (2 * density).toInt(),
                (7 * density).toInt(),
                (2 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(0x147C6CE6)
                setStroke((1 * density).toInt(), 0x3D7C6CE6)
            }
            // 文字本身带一层同色柔光，色块边缘再用淡阴影撑出一圈光晕。
            setShadowLayer(3f * scale * density, 0f, 0f, 0x66A79BFF)
            elevation = 2f * density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                outlineAmbientShadowColor = ACCENT
                outlineSpotShadowColor = ACCENT
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
            }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick(tag) }
            }
        }
    }
}
