package com.leehe.vota.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 背景来源 */
enum class BackgroundMode {
    /** Bing 每日一图（跟随官方 API） */
    BING,

    /** 本地图片（相册选图） */
    LOCAL,

    /** 内置彩色色块（不依赖网络，折射最明显） */
    PATTERN,
}

/** Bing 每日一图的一张图 */
data class BingImage(
    val startDate: String,
    /** 详情页用的 urlbase，形如 `/th?id=OHR.Bbb_ZH-CN123` */
    val urlBase: String,
    val copyright: String,
    /** 默认取 1920x1080 的档位，够用又不至于太占内存 */
    val url1920: String = BING_BASE + urlBase + "_1920x1080.jpg",
    val urlUhd: String = BING_BASE + urlBase + "_UHD.jpg",
    val urlThumb: String = BING_BASE + urlBase + "_400x240.jpg",
) {
    companion object {
        const val BING_BASE = "https://www.bing.com"
    }
}

/**
 * Bing 每日一图。接口本身没有 key、没有配额，只用它返回的 `urlbase` 拼图片地址。
 *
 * 接口：`/HPImageArchive.aspx?format=js&idx=0&n=<count>&mkt=<mkt>`
 * - `idx=0` 是今天，`idx=1` 是昨天……「换一张」就是往后翻 idx
 * - `n` 是一次最多取几张（官方上限 8）
 * - 图片档位：`_1920x1080.jpg` / `_UHD.jpg` / `_400x240.jpg`
 */
object BingWallpaper {

    private const val ARCHIVE = "https://www.bing.com/HPImageArchive.aspx"

    /** 一次最多取 8 张（官方上限） */
    const val MAX_IMAGES = 8

    /**
     * 拉取最近 [count] 天的图，第 0 个是今天。
     * 失败抛异常，由调用方决定降级策略（用缓存 / 保留当前背景）。
     */
    fun fetchImages(count: Int = MAX_IMAGES, mkt: String = "zh-CN"): List<BingImage> {
        val n = count.coerceIn(1, MAX_IMAGES)
        val url = "$ARCHIVE?format=js&idx=0&n=$n&mkt=$mkt"
        val json = Http.getText(url)

        return Regex("""\{"startdate":"(\d+)".*?"urlbase":"([^"]+)".*?"copyright":"([^"]*)"""")
            .findAll(json)
            .map { m ->
                BingImage(
                    startDate = m.groupValues[1],
                    urlBase = m.groupValues[2],
                    copyright = m.groupValues[3],
                )
            }
            .filter { it.urlBase.isNotBlank() }
            .toList()
    }

    /** 下载图片；命中磁盘缓存直接返回，不再发请求 */
    fun loadBitmap(context: Context, image: BingImage, preferUhd: Boolean = false): Bitmap? {
        val url = if (preferUhd) image.urlUhd else image.url1920
        return ImageStore.load(context, url) { ImageStore.cacheFile(context, url) }
    }

    /** 小图，用于确认某天的图能不能下载（省流量） */
    fun loadThumbnail(context: Context, image: BingImage): Bitmap? =
        ImageStore.load(context, image.urlThumb) { ImageStore.cacheFile(context, image.urlThumb) }
}

/** 极简 HTTP：这些图床不需要自定义 TLS，用标准库即可 */
internal object Http {

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url, headers).toString(Charsets.UTF_8)

    /** 下载二进制，带 8 MB 上限保护 */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        request(url, headers)

    private fun request(url: String, headers: Map<String, String>): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) VotaAndroid")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val bytes = conn.inputStream.use { readCapped(it, 8 * 1024 * 1024) }
            if (bytes.isEmpty()) error("empty response")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > cap) error("response larger than ${cap / 1024 / 1024} MB")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

/** 图片磁盘缓存 + 采样解码，避免把 4K 原图整张读进内存 */
object ImageStore {

    fun cacheFile(context: Context, url: String): File {
        val name = url.hashCode().toUInt().toString(16) + ".img"
        return File(context.cacheDir, "bg").apply { mkdirs() }.resolve(name)
    }

    /** 有缓存读缓存，没有就下载再落盘 */
    fun load(context: Context, url: String, file: () -> File): Bitmap? {
        val target = file()
        val bytes = if (target.exists() && target.length() > 0) {
            runCatching { target.readBytes() }.getOrNull()
        } else {
            runCatching { Http.getBytes(url) }
                .onSuccess { runCatching { target.writeBytes(it) } }
                .getOrNull()
        }
        if (bytes == null) return null
        return runCatching { decode(bytes) }.getOrNull()
    }

    /** 按屏幕需要采样解码（对外公开，本地图片也走这里） */
    fun decode(bytes: ByteArray, targetLongEdge: Int = 1440): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
