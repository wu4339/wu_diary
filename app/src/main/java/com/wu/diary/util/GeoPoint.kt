package com.wu.diary.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wu.diary.R
import java.util.Locale

/** 笔记正文里用定位插入的坐标（如「31.230416, 121.473701」）。 */
object GeoPoint {

    /** 纬度在前、经度在后，逗号可中英文。 */
    private val pattern = Regex("(-?\\d{1,3}\\.\\d+)\\s*[,，]\\s*(-?\\d{1,3}\\.\\d+)")

    /** 正文里的一段坐标文本。 */
    data class GeoMatch(val start: Int, val end: Int, val lat: Double, val lng: Double)

    /** 找出正文里所有合法坐标，记录各自在文本中的位置。 */
    fun findAll(text: String): List<GeoMatch> {
        val result = mutableListOf<GeoMatch>()
        pattern.findAll(text).forEach { match ->
            val lat = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val lng = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@forEach
            result.add(GeoMatch(match.range.first, match.range.last + 1, lat, lng))
        }
        return result
    }

    /**
     * 用系统地图打开该坐标；没有地图应用时给出提示。
     * 国内地图 App（高德/腾讯/Google 中国）用的是 GCJ-02，而定位拿到的是 WGS-84，
     * 直接传会让标记点偏移几百米，所以先做一次坐标纠偏。
     * 只带坐标、不带名称：部分地图 App 会把 q 里的中文名称显示成 %E6% 这样的编码。
     */
    fun openMap(context: Context, lat: Double, lng: Double) {
        val (mapLat, mapLng) = wgsToGcj(lat, lng)
        val coords = "$mapLat,$mapLng"
        val uri = Uri.parse("geo:$coords?q=$coords")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure {
                Toast.makeText(context, R.string.map_unavailable, Toast.LENGTH_SHORT).show()
            }
    }

    /** 正文里的记录格式：📍地名(30.270010，120.150020)；地名取不到时写「当前位置」。 */
    fun recordLine(context: Context, name: String?, lat: Double, lng: Double): String {
        val place = name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.location_current)
        val latText = String.format(Locale.US, "%.6f", lat)
        val lngText = String.format(Locale.US, "%.6f", lng)
        return context.getString(R.string.location_record, place, latText, lngText)
    }

    private val PI = Math.PI

    /** 克拉索夫斯基椭球参数。 */
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 判断是否在国内；国外坐标不需要纠偏。 */
    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(x / 12.0 * PI) + 320.0 * Math.sin(x * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    /** WGS-84（GPS）转 GCJ-02（国内地图），保留 6 位小数。 */
    private fun wgsToGcj(lat: Double, lng: Double): Pair<String, String> {
        if (outOfChina(lat, lng)) {
            return String.format(Locale.US, "%.6f", lat) to String.format(Locale.US, "%.6f", lng)
        }
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        val mapLat = lat + dLat
        val mapLng = lng + dLng
        return String.format(Locale.US, "%.6f", mapLat) to
            String.format(Locale.US, "%.6f", mapLng)
    }
}
