package com.wu.diary.util

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * 用系统 Geocoder 把经纬度反查成「市/区/路/小区」这类地点名。
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
                address?.let(::compose)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            Handler(Looper.getMainLooper()).post { onResult(name) }
        }.start()
    }

    /** 依次拼上市、区、路、门牌和小区/POI 名，重复的部分跳过。 */
    private fun compose(address: android.location.Address): String {
        val parts = mutableListOf<String>()
        fun add(part: String?) {
            val value = part?.trim().orEmpty()
            if (value.isNotEmpty() && value !in parts) parts.add(value)
        }
        add(address.locality ?: address.subAdminArea ?: address.adminArea)
        add(address.subLocality)
        add(address.thoroughfare)
        add(address.featureName)
        return parts.joinToString("")
    }
}
