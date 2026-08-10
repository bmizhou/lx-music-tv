package com.lxmusic.tv.network

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐 weapi 请求加密工具
 *
 * 网易云未登录（游客态）访问歌单详情/歌曲列表时，明文接口（api/playlist/detail）
 * 最多只返回前 10 首且 trackCount 被截成可见数；只有加密的 weapi 接口能返回
 * 完整歌单（与 YesPlayMusic 等客户端一致，无需登录即可拉全）。
 *
 * 加密算法（与官方一致）：
 * 1. AES-128-CBC(PKCS5) 用固定密钥 PRESET_KEY 加密明文 JSON → base64 字符串
 * 2. AES-128-CBC(PKCS5) 用随机 16 位密钥 secret 加密上一步的 base64 字符串 → base64（params）
 * 3. RSA：将 secret 字节反转后作为大整数，用固定公钥 (e=65537, n=MODULUS) 做模幂 → 256 位十六进制（encSecKey）
 *
 * 公钥取自 NeteaseCloudMusicApi 当前维护版本（网易云会轮换公钥，旧模数会被服务器拒绝）。
 */
object NeteaseWeApi {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"          // AES 第一轮固定密钥
    private const val IV = "0102030405060708"                   // AES CBC 偏移量（16 字节）
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    // 网易云现行 RSA 公钥模数（256 位十六进制，128 字节）
    private const val MODULUS = "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152" +
            "b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557" +
            "c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047" +
            "b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private val PUBLIC_EXPONENT = BigInteger("010001", 16)      // 65537
    private val MODULUS_BI = BigInteger(MODULUS, 16)
    private val secureRandom = SecureRandom()

    /** 生成 16 位随机字符串（BASE62 字符集），作为第二轮 AES 密钥与 RSA 明文 */
    private fun randomSecret(): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            sb.append(BASE62[secureRandom.nextInt(BASE62.length)])
        }
        return sb.toString()
    }

    /** AES-128-CBC + PKCS5Padding 加密，返回密文字节 */
    private fun aesCbcEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(plain)
    }

    /**
     * 对明文 JSON 字符串做 weapi 加密
     * @return Pair(params, encSecKey)，直接作为表单字段提交
     */
    fun encrypt(plainJson: String): Pair<String, String> {
        val secret = randomSecret()
        // 第一轮：用固定密钥加密明文，结果转 base64 字符串
        val innerBytes = aesCbcEncrypt(plainJson.toByteArray(Charsets.UTF_8), PRESET_KEY.toByteArray(Charsets.UTF_8))
        val innerBase64 = Base64.encodeToString(innerBytes, Base64.NO_WRAP)
        // 第二轮：用随机密钥加密第一轮结果的 base64 字符串，再转 base64 → params
        val outerBytes = aesCbcEncrypt(innerBase64.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))
        val params = Base64.encodeToString(outerBytes, Base64.NO_WRAP)
        // RSA：secret 字节反转后模幂 → 256 位十六进制 encSecKey
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val reversed = ByteArray(secretBytes.size) { secretBytes[secretBytes.size - 1 - it] }
        val encSecKey = BigInteger(1, reversed)
            .modPow(PUBLIC_EXPONENT, MODULUS_BI)
            .toString(16)
            .padStart(256, '0')
        return params to encSecKey
    }
}
