package com.lxmusic.tv.data.source

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 酷我 wbd 接口加密工具（移植 lxserver musicSdk/kw/util.js 的 wbdCrypto）
 *
 * - buildParam: 请求参数 AES-128-ECB 加密 + MD5 签名
 * - decodeData: 响应 base64 + AES-128-ECB 解密
 */
object KwCrypto {
    private const val APP_ID = "y67sprxhhpws"

    // 固定 16 字节 AES key（来自酷我前端）
    private val AES_KEY = byteArrayOf(
        112.toByte(), 87.toByte(), 39.toByte(), 61.toByte(),
        199.toByte(), 250.toByte(), 41.toByte(), 191.toByte(),
        57.toByte(), 68.toByte(), 45.toByte(), 114.toByte(),
        221.toByte(), 94.toByte(), 140.toByte(), 228.toByte()
    )

    private fun aesEncrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        return cipher.doFinal(data)
    }

    private fun aesDecrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY, "AES"))
        return cipher.doFinal(data)
    }

    private fun md5HexUpper(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * 生成 wbd 接口请求参数串（data/time/appId/sign）
     */
    fun buildParam(jsonData: String): String {
        val data = jsonData.toByteArray(Charsets.UTF_8)
        val time = System.currentTimeMillis()
        val encodeData = android.util.Base64.encodeToString(aesEncrypt(data), android.util.Base64.NO_WRAP)
        val sign = md5HexUpper("$APP_ID$encodeData$time")
        return "data=${java.net.URLEncoder.encode(encodeData, "UTF-8")}&time=$time&appId=$APP_ID&sign=$sign"
    }

    /**
     * 解密 wbd 接口响应（base64 → AES 解密 → 字符串）
     */
    fun decodeData(base64Result: String): String {
        val decoded = android.util.Base64.decode(
            java.net.URLDecoder.decode(base64Result, "UTF-8"),
            android.util.Base64.DEFAULT
        )
        return String(aesDecrypt(decoded), Charsets.UTF_8)
    }
}
