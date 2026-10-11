/*
 * Copyright 2026 leehe-byte
 * SPDX-License-Identifier: Apache-2.0
 *
 * Kotlin 移植自 VIVO-OTA-Tracker（https://github.com/JerryTse-OSS/VIVO-OTA-Tracker，
 * Apache License 2.0）的 Python 实现，并为 Android 做了修改。
 * 已修改的文件清单、改动范围与上游对应关系见仓库根目录的 NOTICE。
 */
package com.leehe.vota.crypto

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vivo OTA 协议实现（从 VivoOtaTracker.py 移植）。
 *
 * 协议 = AES-128-CBC/PKCS7 加密后的 JSON 体 + 16 字节自定义头，
 * 整体再 URL-safe Base64 编码，作为 `jvq_param=` 表单参数发给服务端。
 * 响应同构：URL-safe Base64 → 拆头 → AES 解密 → JSON。
 *
 * 密钥/IV 来自对 libvivoseckey_n4.so 的逆向（原项目 Python 版常量）。
 */
object VivoProtocol {

    const val TOKEN_NATIVE = "jnisgmain_v2@com.bbk.updater"

    /** AES-128-CBC 的 IV（16 字节） */
    private val AES_IV = "047cd76d65d3b28b4ccc2c0246681aa6".hexToBytes()

    /** key_version = 1 的密钥 */
    private val AES_KEY_KV1 = "5da590863052089893199b2a901b6470".hexToBytes()

    /** key_version = 2 的密钥（默认） */
    private val AES_KEY_KV2 = "836e75afddae728551ad22b2bae6ca57".hexToBytes()

    /** 服务端公钥哈希（协议里未使用，保留用于校验/说明） */
    const val PUB_KEY_HASH = "077c62d0246d6572d2760e3247a1ebcaf68eb4161aa13353583c445ce71fc49c"

    const val BASE_URL = "https://sysupgrade.vivo.com.cn"
    const val UPDATE_ENDPOINT = "/vgc/v2/getVgcAndPatch.do?"
    const val REDIR_ENDPOINT = "/pk/redirPost.do"

    /** 下载直链前缀（原项目里由 pkName 拼出，无需再走 redirPost） */
    const val DOWNLOAD_PREFIX = "https://sysuptxdl.vivo.com.cn/upgrade/oem/files/"

    private const val MSG_TYPE_ENCRYPT = 5

    // ------------------------------------------------------------------
    // AES
    // ------------------------------------------------------------------

    fun aesEncrypt(plaintext: ByteArray, keyVersion: Int = 2): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(keyVersion), IvParameterSpec(AES_IV))
        return cipher.doFinal(plaintext)
    }

    fun aesDecrypt(ciphertext: ByteArray, keyVersion: Int = 2): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyFor(keyVersion), IvParameterSpec(AES_IV))
        return cipher.doFinal(ciphertext)
    }

    private fun keyFor(keyVersion: Int) = SecretKeySpec(
        if (keyVersion == 1) AES_KEY_KV1 else AES_KEY_KV2,
        "AES"
    )

    // ------------------------------------------------------------------
    // 协议封包
    // ------------------------------------------------------------------

    /**
     * 组装协议包：`[2B 头长][8B CRC32][头字段][加密体]`
     *
     * 头字段 = `[2B 版本=1][1B token 长度][token][2B keyVersion][1B msgType]`
     */
    fun buildProtocolPackage(
        msgType: Int,
        keyVersion: Int,
        token: String,
        data: ByteArray,
    ): ByteArray {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val headerTotalLen = 16 + tokenBytes.size

        val header = ByteArrayOutputStream()
        header.write(byteArrayOf(0, 1))                         // version = 1 (2B, big endian)
        header.write(tokenBytes.size)                           // token 长度 (1B)
        header.write(tokenBytes)                                // token
        header.write(byteArrayOf((keyVersion ushr 8).toByte(), keyVersion.toByte()))
        header.write(msgType)
        val headerFields = header.toByteArray()

        val crc = CRC32().apply { update(headerFields) }.value

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((headerTotalLen ushr 8).toByte(), headerTotalLen.toByte()))
        // CRC 占 8 字节：原实现把 32 位 CRC 放在低 4 字节（Python 的 to_bytes(8,"big")）
        out.write(ByteArray(8) { i -> ((crc ushr (8 * (7 - i))).toByte()) })
        out.write(headerFields)
        out.write(data)
        return out.toByteArray()
    }

    /** 头前 2 字节是大端头长，跳过它剩下的就是加密体 */
    fun extractEncryptedBody(fullPackage: ByteArray): ByteArray {
        val headerLen = ((fullPackage[0].toInt() and 0xFF) shl 8) or (fullPackage[1].toInt() and 0xFF)
        return fullPackage.copyOfRange(headerLen, fullPackage.size)
    }

    // ------------------------------------------------------------------
    // Base64（URL-safe，带 padding 容错）
    // ------------------------------------------------------------------

    fun base64UrlEncode(data: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun base64UrlDecode(data: String): ByteArray {
        // 先做一次 URL 解码（对齐 Java 的 URLDecoder.decode）
        val decoded = urlDecode(data)
            .replace('-', '+')
            .replace('_', '/')
            .replace(" ", "+")
        val padLen = (4 - decoded.length % 4) % 4
        val padded = decoded + "=".repeat(padLen)
        return java.util.Base64.getDecoder().decode(padded)
    }

    /** 只处理 %XX 转义，不把 '+' 当空格（base64 里 '+' 是有效字符） */
    private fun urlDecode(input: String): String {
        if ('%' !in input) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 3 <= input.length) {
                val hex = input.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 加解密入口
    // ------------------------------------------------------------------

    /** 明文 → `jvq_param` 的值 */
    fun encryptToJvq(plaintext: String, keyVersion: Int = 2): String {
        val encrypted = aesEncrypt(plaintext.toByteArray(Charsets.UTF_8), keyVersion)
        val pkg = buildProtocolPackage(MSG_TYPE_ENCRYPT, keyVersion, TOKEN_NATIVE, encrypted)
        return base64UrlEncode(pkg)
    }

    /** 服务端响应（URL-safe Base64）→ 明文 JSON */
    fun decryptResponse(responseB64: String, keyVersion: Int = 2): String {
        val fullPackage = base64UrlDecode(responseB64.trim())
        val body = extractEncryptedBody(fullPackage)
        return String(aesDecrypt(body, keyVersion), Charsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex length must be even: $length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
