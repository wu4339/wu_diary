package com.wu.diary.util

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.wu.diary.R

/** 删除确认弹窗：比系统默认更窄、更高，确认按钮直接执行 [onConfirm]。 */
fun showDeleteConfirm(activity: Activity, onConfirm: () -> Unit) {
    val dialog = AlertDialog.Builder(activity)
        .setMessage(R.string.delete_confirm)
        .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
        val density = activity.resources.displayMetrics.density
        dialog.window?.setLayout(
            (236 * density).toInt(),
            (216 * density).toInt()
        )
    }
    dialog.show()
}
