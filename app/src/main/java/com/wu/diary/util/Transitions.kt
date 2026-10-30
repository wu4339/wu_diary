package com.wu.diary.util

import android.app.Activity
import com.wu.diary.R
import com.wu.diary.data.Settings

/** 页面打开 / 关闭时按设置播放切换动画。 */
object Transitions {

    /** 打开新页面：调用方 startActivity 之后调用。 */
    @Suppress("DEPRECATION")
    fun open(from: Activity) {
        if (Settings(from).transitionStyle == Settings.TRANSITION_EXPAND) {
            // 原地展开：新页面缩放淡入，下面的页面保持不动。
            from.overridePendingTransition(R.anim.editor_grow_in, 0)
        } else {
            from.overridePendingTransition(R.anim.page_slide_in_right, R.anim.page_slide_out_left)
        }
    }

    /** 关闭当前页面：finish 之后调用。 */
    @Suppress("DEPRECATION")
    fun close(activity: Activity) {
        if (Settings(activity).transitionStyle == Settings.TRANSITION_EXPAND) {
            // 原地关闭：当前页面缩放淡出，下面的页面保持不动。
            activity.overridePendingTransition(0, R.anim.editor_shrink_out)
        } else {
            activity.overridePendingTransition(
                R.anim.page_slide_in_left,
                R.anim.page_slide_out_right
            )
        }
    }
}
