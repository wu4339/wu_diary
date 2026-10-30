package com.wu.diary.util

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * 用系统 Geocoder 把经纬度反查成「市/区/街道」这类地点名。
 * 查询放在后台线程，结果回主线程回调；设备不支持或查询失败时回调 null。
 */
object GeoName {

    fun resolve(context: Context, lat: Double, lng: Double, onResult: (String?) -> Unit) {
        val appContext = context.applicationContext
        if (!Geocoder.isPresent()) {
            onResult(null)
            return
        }
        Thread {
            val name = runCatching {
                @Suppress("DEPRECATION")
                val address = Geocoder(appContext, Locale.getDefault())
                    .getFromLocation(lat, lng, 1)
                    ?.firstOrNull()
                address?.let {
                    listOfNotNull(
                        it.locality?.takeIf { part -> part.isNotBlank() }
                            ?: it.subAdminArea?.takeIf { part -> part.isNotBlank() }
                            ?: it.adminArea?.takeIf { part -> part.isNotBlank() },
                        it.subLocality?.takeIf { part -> part.isNotBlank() },
                        it.thoroughfare?.takeIf { part -> part.isNotBlank() }
                    ).joinToString("")
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            Handler(Looper.getMainLooper()).post { onResult(name) }
        }.start()
    }
}
