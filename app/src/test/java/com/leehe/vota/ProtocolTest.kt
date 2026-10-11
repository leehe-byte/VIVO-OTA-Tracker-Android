package com.leehe.vota

import com.leehe.vota.crypto.VivoProtocol
import com.leehe.vota.data.DeviceConfig
import com.leehe.vota.data.DeviceInfoReader
import com.leehe.vota.data.OtaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.CRC32

/**
 * 协议自测：不联网，只验证加密封包、解密、参数拼装、响应解析这四段是否与原 Python 实现一致。
 */
class ProtocolTest {

    @Test
    fun `jvq package layout matches the python implementation`() {
        val plaintext = "a=1&b=2"
        val jvq = VivoProtocol.encryptToJvq(plaintext, 2)
        val pkg = Base64.getUrlDecoder().decode(jvq)

        val headerLen = ((pkg[0].toInt() and 0xFF) shl 8) or (pkg[1].toInt() and 0xFF)
        // 16 字节固定头 + token 长度
        assertEquals(16 + VivoProtocol.TOKEN_NATIVE.length, headerLen)

        // CRC32 只占低 4 字节
        val crcStored = pkg.copyOfRange(2, 10).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val crc = CRC32().apply { update(pkg.copyOfRange(10, headerLen)) }.value
        assertEquals(crc, crcStored)

        // 2B 版本 + 1B token 长度 + token + 2B keyVersion + 1B msgType
        assertEquals(1, ((pkg[10].toInt() and 0xFF) shl 8) or (pkg[11].toInt() and 0xFF))
        assertEquals(VivoProtocol.TOKEN_NATIVE.length, pkg[12].toInt())
        val token = String(pkg, 13, VivoProtocol.TOKEN_NATIVE.length, Charsets.UTF_8)
        assertEquals(VivoProtocol.TOKEN_NATIVE, token)
        val keyVersionOffset = 13 + VivoProtocol.TOKEN_NATIVE.length
        assertEquals(2, ((pkg[keyVersionOffset].toInt() and 0xFF) shl 8) or (pkg[keyVersionOffset + 1].toInt() and 0xFF))
        assertEquals(5, pkg[keyVersionOffset + 2].toInt())

        // 负载是 AES-128-CBC/PKCS7，所以一定是 16 的整数倍
        val body = pkg.copyOfRange(headerLen, pkg.size)
        assertEquals(0, body.size % 16)
    }

    @Test
    fun `decryptResponse round trips an encrypted response`() {
        val json = """{"version":"16.2.13.0.W10","pkName":"PD2324_A_16.2.13.0.W10-update.zip","pkLen":"538968064"}"""
        val encrypted = VivoProtocol.aesEncrypt(json.toByteArray(Charsets.UTF_8), 2)
        val pkg = VivoProtocol.buildProtocolPackage(5, 2, VivoProtocol.TOKEN_NATIVE, encrypted)
        val b64 = Base64.getUrlEncoder().encodeToString(pkg)

        // 真实响应以 ACw / ACo 开头（44 / 43 字节头的 base64 前缀）
        assertTrue(b64.startsWith("AC"))

        val decoded = VivoProtocol.decryptResponse(b64, 2)
        assertEquals(json, decoded)
    }

    @Test
    fun `key version 1 also round trips`() {
        val text = "hello vivo"
        val encrypted = VivoProtocol.aesEncrypt(text.toByteArray(Charsets.UTF_8), 1)
        val back = String(VivoProtocol.aesDecrypt(encrypted, 1), Charsets.UTF_8)
        assertEquals(text, back)
    }

    @Test
    fun `phone request params contain the derived version fields`() {
        val config = DeviceConfig(
            deviceType = "phone",
            modelSwVer = "PD2408",
            deviceModel = "V2408A",
            swVersion = "16.1.16.5.W10",
            androidVer = 16,
            isFull = true,
        )
        val params = OtaRepository.buildRequestParams(config)

        // ".W" 版本要补 .V000L1 后缀
        assertEquals("16.1.16.5.W10.V000L1", params["swVer"])
        assertEquals("PD2408_A_16.1.16.5.W10.V000L1", params["fullVer"])
        assertEquals("PD2408_N_PD2408MA_16.1.16.5.W10.V000L1", params["version"])
        assertEquals("PD2408MA", params["hwVer"])
        assertEquals("1", params["isFull"])
        assertEquals("phone", params["dType"])
        assertEquals(15, params["imei"]!!.length)
    }

    @Test
    fun `tablet request params follow the tablet branch`() {
        val config = DeviceConfig(
            deviceType = "tablet",
            modelSwVer = "DPD2106",
            deviceModel = "PA2170",
            swVersion = "8.7.22",
            androidVer = 14,
            isFull = false,
        )
        val params = OtaRepository.buildRequestParams(config)

        // 没有 ".W" 就不加后缀
        assertEquals("8.7.22", params["swVer"])
        assertEquals("tablet", params["dType"])
        assertEquals("0", params["isFull"])
        assertEquals("Funtouch 14.0", params["romVersion"])
        assertEquals("A0000000000000A", params["snp"])
        assertEquals("DPD2106+DPD2106B", params["oemProjects"])
        assertEquals("-1", params["ms"])
    }

    @Test
    fun `response parsing extracts firmware fields`() {
        // 服务端返回的 URL 是 JSON 转义过的：https:\/\/…
        val json = """{"message":"ok","version":"16.2.13.0.W10","pkName":"PD2324_A_16.2.13.0.W10-update.zip","pkLen":"538968064","h5Url":"https:\/\/example.com\/a.html"}"""
        val info = OtaRepository.parseUpdateResponse(json)

        assertEquals("16.2.13.0.W10", info.version)
        assertEquals("PD2324_A_16.2.13.0.W10-update.zip", info.filename)
        assertEquals(538_968_064L, info.sizeBytes)
        assertEquals(514L, info.sizeMb)
        assertNotNull(info.downloadUrl)
        assertTrue(info.downloadUrl!!.endsWith(info.filename!!))
        // \/ 必须还原成 /
        assertEquals("https://example.com/a.html", info.changelogUrl)
        assertEquals(
            "https://sysuptxdl.vivo.com.cn/upgrade/oem/files/PD2324_A_16.2.13.0.W10-update.zip",
            info.downloadUrl,
        )
    }

    @Test
    fun `bbk version string is parsed into sw model and version`() {
        val parsed = DeviceInfoReader.parseBbkVersion("PD2324F_A_16.2.13.0.W10.V000L1")
        assertEquals("PD2324", parsed.swModel)
        assertEquals("PD2324F", parsed.deviceModelCandidate)
        assertEquals("16.2.13.0.W10", parsed.swVersion)
    }

    @Test
    fun `empty bbk version is tolerated`() {
        val parsed = DeviceInfoReader.parseBbkVersion("")
        assertEquals(null, parsed.swModel)
        assertEquals(null, parsed.swVersion)
    }

    @Test
    fun `android release maps to major version not api level`() {
        assertEquals(16, DeviceInfoReader.majorVersionOf("16"))
        assertEquals(14, DeviceInfoReader.majorVersionOf("14"))
        assertEquals(15, DeviceInfoReader.majorVersionOf("15.0"))
        assertEquals(null, DeviceInfoReader.majorVersionOf(""))
    }

    @Test
    fun `url safe base64 without padding decodes`() {
        val plain = ByteArray(40) { (it * 7).toByte() }
        val encoded = VivoProtocol.base64UrlEncode(plain)
        assertTrue(encoded.none { it == '=' || it == '+' || it == '/' })
        assertTrue(VivoProtocol.base64UrlDecode(encoded).contentEquals(plain))
    }
}
