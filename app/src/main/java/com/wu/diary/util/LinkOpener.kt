package com.wu.diary.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.wu.diary.R

/**
 * 打开笔记里的链接：优先交给装了该网站对应 App 的应用（如抖音、B 站），
 * 系统里没有对应 App 时再退回浏览器。
 */
object LinkOpener {

    /** 域名后缀 → 应用包名。应用没做 Android App Links 校验（如大部分国内 App）时也能直接拉起。 */
    private val appPackages = listOf(
        "douyin.com" to "com.ss.android.ugc.aweme",
        "iesdouyin.com" to "com.ss.android.ugc.aweme",
        "bilibili.com" to "tv.danmaku.bili",
        "b23.tv" to "tv.danmaku.bili",
        "zhihu.com" to "com.zhihu.android",
        "xiaohongshu.com" to "com.xingin.xhs",
        "xhslink.com" to "com.xingin.xhs",
        "weibo.com" to "com.sina.weibo",
        "weibo.cn" to "com.sina.weibo",
        "taobao.com" to "com.taobao.taobao",
        "tmall.com" to "com.taobao.taobao",
        "tb.cn" to "com.taobao.taobao",
        "jd.com" to "com.jingdong.app.mall",
        "youtube.com" to "com.google.android.youtube",
        "youtu.be" to "com.google.android.youtube",
        "instagram.com" to "com.instagram.android",
        "x.com" to "com.twitter.android",
        "twitter.com" to "com.twitter.android",
        "music.163.com" to "com.netease.cloudmusic",
        "spotify.com" to "com.spotify.music",
        "github.com" to "com.github.android",
        "reddit.com" to "com.reddit.frontpage"
    )

    /** 已知浏览器包名：找不到对应 App 时这些作为最后兜底，不被当作「对应 App」。 */
    private val browsers = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.android.browser",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.sec.android.app.sbrowser",
        "com.mi.globalbrowser",
        "com.UCMobile",
        "com.UCMobile.intl",
        "com.quark.browser",
        "com.baidu.searchbox",
        "com.tencent.mtt",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.huawei.browser",
        "com.kiwibrowser.browser",
        "com.brave.browser",
        "com.ecosia.android",
        "mark.via",
        "org.chromium.chrome"
    )

    fun open(context: Context, uri: Uri) {
        val target = withScheme(uri)
        val scheme = target.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            startSafely(context, Intent(Intent.ACTION_VIEW, target))
            return
        }

        val mapped = packageForHost(target.host)
        if (mapped != null && launchIn(context, target, mapped)) return
        if (launchInRegisteredApp(context, target)) return
        startSafely(context, Intent(Intent.ACTION_VIEW, target))
    }

    /** 有些链接文本不带 http/https，补上后再打开。 */
    private fun withScheme(uri: Uri): Uri =
        if (uri.scheme.isNullOrEmpty()) Uri.parse("https://$uri") else uri

    private fun packageForHost(host: String?): String? {
        val normalized = host?.lowercase()?.removePrefix("www.") ?: return null
        return appPackages.firstOrNull { (domain, _) ->
            normalized == domain || normalized.endsWith(".$domain")
        }?.second
    }

    /** 用指定 App 打开；该 App 没有注册这个网址时返回 false。 */
    private fun launchIn(context: Context, uri: Uri, packageName: String): Boolean =
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(packageName))
            true
        }.getOrDefault(false)

    /** 系统里注册了该网址的非浏览器应用（例如某些 App 的 App Links），直接用它打开。 */
    private fun launchInRegisteredApp(context: Context, uri: Uri): Boolean {
        val handlers = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in browsers }
        return handlers.any { launchIn(context, uri, it) }
    }

    private fun startSafely(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            Toast.makeText(context, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
            false
        }
}
