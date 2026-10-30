package com.wu.diary.util

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import com.wu.diary.R

/** 删除确认弹窗：白色圆角卡片，标题 + 说明 + 并排的取消 / 删除按钮。 */
fun showDeleteConfirm(activity: Activity, onConfirm: () -> Unit) {
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val content = LayoutInflater.from(activity).inflate(R.layout.dialog_delete_confirm, null)
    dialog.setContentView(content)
    content.findViewById<View>(R.id.btnDeleteCancel).setOnClickListener { dialog.dismiss() }
    content.findViewById<View>(R.id.btnDeleteConfirm).setOnClickListener {
        dialog.dismiss()
        onConfirm()
    }
    // 卡片宽度比屏幕窄一点。宽度在显示前就定好，避免弹出一瞬间先按默认宽度再跳变。
    val metrics = activity.resources.displayMetrics
    val width = metrics.widthPixels - (72 * metrics.density).toInt()
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.35f)
        setGravity(Gravity.CENTER)
        setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}
