/*
 * Copyright 2026 leehe-byte
 * SPDX-License-Identifier: Apache-2.0
 *
 * Kotlin 移植自 VIVO-OTA-Tracker（https://github.com/JerryTse-OSS/VIVO-OTA-Tracker，
 * Apache License 2.0）的 SSLAdapter / http_post 传输层，并为 Android 做了修改：
 * 用 HttpURLConnection + 自定义 TrustManager 替代 requests + urllib3。
 * 改动范围见仓库根目录的 NOTICE。
 */
package com.leehe.vota.net

import com.leehe.vota.crypto.VivoProtocol
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Vivo OTA 服务端的 HTTP 客户端。
 *
 * 用 [HttpURLConnection] 而不是 OkHttp：官方服务端的 TLS 配置（旧 cipher suite）
 * 在 OkHttp 的默认 ConnectionSpec 下会握手失败，原 Python 版为此专门写了
 * `SSLAdapter`（`set_ciphers('DEFAULT:@SECLEVEL=1')` + 不校验证书）。
 * 标准库 + 自定义 TrustManager 能达到同样效果，且不引入额外依赖。
 */
object OtaHttpClient {

    private const val TIMEOUT_MS = 60_000

    private val trustAllSocketFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
            .socketFactory
    }

    /**
     * 发送加密请求并返回解密后的明文。
     *
     * @param endpoint [VivoProtocol.UPDATE_ENDPOINT] 或 [VivoProtocol.REDIR_ENDPOINT]
     * @return 明文响应；若服务端返回的不是加密包（错误页/限流），原样返回带 `[Error]` 前缀
     */
    fun sendEncryptedRequest(
        plaintext: String,
        endpoint: String = VivoProtocol.UPDATE_ENDPOINT,
        keyVersion: Int = 2,
    ): String {
        val jvqParam = VivoProtocol.encryptToJvq(plaintext, keyVersion)
        val raw = postForm(
            url = VivoProtocol.BASE_URL + endpoint,
            body = "jvq_param=$jvqParam",
        )
        // 加密包一定是 base64 且以 ACw/ACo 开头（头长 44/43 的 base64 前缀）
        if (!raw.startsWith("ACw") && !raw.startsWith("ACo")) {
            return "[Error] $raw"
        }
        return VivoProtocol.decryptResponse(raw, keyVersion)
    }

    fun postForm(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "okhttp/4.3.23")
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Accept-Encoding", "gzip")
            if (this is HttpsURLConnection) {
                sslSocketFactory = trustAllSocketFactory
                hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
        }

        try {
            // 原实现不做 URL 编码，body 按 UTF-8 直接写（服务端只认这种形态）
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readAll(it) } ?: ByteArray(0)
            if (code !in 200..299) {
                throw OtaNetworkException("HTTP $code: ${decodeText(bytes).take(300)}")
            }
            return decodeText(bytes)
        } catch (e: OtaNetworkException) {
            throw e
        } catch (e: Exception) {
            throw OtaNetworkException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        var bytes = out.toByteArray()
        // 手动 gunzip：服务端在 Accept-Encoding: gzip 下可能返回 gzip 但不带 Content-Encoding 头
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            bytes = runCatching {
                GZIPInputStream(bytes.inputStream()).use { readAll(it) }
            }.getOrDefault(bytes)
        }
        return bytes
    }

    /**
     * 依次尝试 utf-8 / gbk / gb18030 / gb2312 / latin-1（对齐原 Python 实现的多编码回退），
     * 最后退回替换非法字符的 utf-8。
     */
    private fun decodeText(bytes: ByteArray): String {
        for (name in listOf("UTF-8", "GBK", "GB18030", "GB2312", "ISO-8859-1")) {
            val decoded = runCatching { bytes.toString(charset(name)) }.getOrNull()
            if (decoded != null) return decoded
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

class OtaNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
