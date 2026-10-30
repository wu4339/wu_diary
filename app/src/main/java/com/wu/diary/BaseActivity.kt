package com.wu.diary

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wu.diary.util.Transitions

/**
 * 所有页面继承它，统一按设置里的「切换效果」播放打开 / 关闭动画。
 * 重写 startActivity 与 finish，页面代码不需要各自处理转场。
 */
open class BaseActivity : AppCompatActivity() {

    private var skipCloseTransition = false

    /** 紧接着的 finish 不播放关闭动画（例如保存后直接跳到另一个页面）。 */
    protected fun skipCloseTransitionOnce() {
        skipCloseTransition = true
    }

    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        Transitions.open(this)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        super.startActivity(intent, options)
        Transitions.open(this)
    }

    override fun finish() {
        val skip = skipCloseTransition
        skipCloseTransition = false
        super.finish()
        if (!skip) Transitions.close(this)
    }
}
