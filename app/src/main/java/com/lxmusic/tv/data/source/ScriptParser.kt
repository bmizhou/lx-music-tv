package com.lxmusic.tv.data.source

import com.lxmusic.tv.data.model.*
import java.util.regex.Pattern

/**
 * 播放源脚本解析器
 * 负责解析洛雪音乐JS播放源文件
 */
class ScriptParser {

    /** 匹配 \xNN 十六进制转义（Kotlin 字符串中 \\ 表示单个反斜杠） */
    private val HEX_ESCAPE_REGEX = Regex("\\\\x([0-9a-fA-F]{2})")

    /**
     * 解析脚本内容
     * @param scriptContent JS脚本内容
     * @return 解析后的播放源信息
     */
    fun parse(scriptContent: String): ParseResult {
        return try {
            // 0. 先解码 \xNN 十六进制转义：混淆器会把关键字符串（如 'lx'）编码为
            //    '\x6c\x78' 形式（globalThis['\x6c\x78'] = globalThis['lx']），
            //    不解码则下面的字面量匹配（globalThis['lx']、kw: 等）全部落空，
            //    导致洛雪 PC 能导入的混淆源被判「脚本格式不正确」。
            val decoded = decodeHexEscapes(scriptContent)

            // 1. 解析元数据（@name 等注解在文件头注释里，不受混淆影响，用原文即可）
            val metadata = parseMetadata(scriptContent)

            // 2. 解析支持的平台和音质（用解码后的内容：混淆源里 kw:/sources:/音质 也是编码的）
            val platforms = parsePlatforms(decoded)

            // 3. 验证脚本格式（用解码后的内容，否则 globalThis['\x6c\x78'] 匹配不上字面量）
            if (!validateScript(decoded)) {
                return ParseResult.Error("脚本格式不正确")
            }

            ParseResult.Success(
                metadata = metadata,
                platforms = platforms,
                scriptContent = scriptContent
            )
        } catch (e: Exception) {
            ParseResult.Error("解析脚本失败: ${e.message}")
        }
    }

    /**
     * 解码 \xNN 十六进制转义（如 '\x6c\x78' → 'lx'）。
     * 仅供格式校验/平台识别使用，不影响存入数据库的原始脚本内容。
     * 注意：替换串可能含 '$'（如 \x24 → '$'），须用 lambda 形式避免被当作分组引用。
     */
    private fun decodeHexEscapes(input: String): String {
        return HEX_ESCAPE_REGEX.replace(input) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
    }

    /**
     * 解析脚本元数据
     */
    private fun parseMetadata(scriptContent: String): ScriptMetadata {
        val name = extractAnnotation(scriptContent, "@name") ?: "未知源"
        val description = extractAnnotation(scriptContent, "@description")
        val version = extractAnnotation(scriptContent, "@version")
        val author = extractAnnotation(scriptContent, "@author")
        val homepage = extractAnnotation(scriptContent, "@homepage")

        return ScriptMetadata(
            name = name,
            description = description,
            version = version,
            author = author,
            homepage = homepage
        )
    }

    /**
     * 从脚本中提取注解信息
     * 用 [^\r\n]+ 匹配「@注解 后的整行内容」：兼容 LF/CRLF 行尾。
     * 旧正则 (.+?)(?:\n|$) 在 CRLF（\r\n）文件上存在引擎差异（JS/Java 会跨过 \r\n 匹配到错误位置），
     * 导致 @name 解析为 null → 播放源显示「未知源」（洛雪 PC 正常是因为其解析器不同）。
     */
    private fun extractAnnotation(scriptContent: String, annotation: String): String? {
        val pattern = Pattern.compile("$annotation\\s+([^\\r\\n]+)")
        val matcher = pattern.matcher(scriptContent)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else {
            null
        }
    }

    /**
     * 解析支持的平台
     */
    private fun parsePlatforms(scriptContent: String): Map<MusicPlatform, SourcePlatformInfo> {
        val platforms = mutableMapOf<MusicPlatform, SourcePlatformInfo>()

        // 检查每个平台的支持情况
        for (platform in MusicPlatform.entries) {
            if (scriptContent.contains("sources:") && scriptContent.contains("${platform.key}:")) {
                val platformInfo = parsePlatformInfo(scriptContent, platform)
                if (platformInfo != null) {
                    platforms[platform] = platformInfo
                }
            }
        }

        // 如果没有找到明确的平台定义，尝试从代码中推断
        if (platforms.isEmpty()) {
            platforms.putAll(inferPlatforms(scriptContent))
        }

        return platforms
    }

    /**
     * 解析单个平台信息
     */
    private fun parsePlatformInfo(scriptContent: String, platform: MusicPlatform): SourcePlatformInfo? {
        // 查找平台配置块
        val platformPattern = Pattern.compile("${platform.key}:\\s*\\{([^}]+)\\}")
        val matcher = platformPattern.matcher(scriptContent)

        if (!matcher.find()) return null

        val platformBlock = matcher.group(1) ?: return null

        // 解析actions
        val actions = mutableListOf<String>()
        if (platformBlock.contains("musicUrl")) actions.add("musicUrl")
        if (platform.key == "local") {
            if (platformBlock.contains("lyric")) actions.add("lyric")
            if (platformBlock.contains("pic")) actions.add("pic")
        }

        // 解析qualitys
        val qualitys = mutableListOf<AudioQuality>()
        for (quality in AudioQuality.entries) {
            if (platformBlock.contains("'${quality.key}'") || platformBlock.contains("\"${quality.key}\"")) {
                qualitys.add(quality)
            }
        }

        // 获取平台名称
        val namePattern = Pattern.compile("name:\\s*['\"](.+?)['\"]")
        val nameMatcher = namePattern.matcher(platformBlock)
        val name = if (nameMatcher.find()) nameMatcher.group(1) else platform.displayName

        return SourcePlatformInfo(
            name = name ?: platform.displayName,
            actions = actions,
            qualitys = qualitys
        )
    }

    /**
     * 从脚本代码中推断支持的平台
     */
    private fun inferPlatforms(scriptContent: String): Map<MusicPlatform, SourcePlatformInfo> {
        val platforms = mutableMapOf<MusicPlatform, SourcePlatformInfo>()

        // 检查常见的平台关键字
        val platformKeywords = mapOf(
            MusicPlatform.KW to listOf("酷我", "kuwo", "kw"),
            MusicPlatform.KG to listOf("酷狗", "kugou", "kg"),
            MusicPlatform.TX to listOf("QQ音乐", "qq", "tx"),
            MusicPlatform.WY to listOf("网易云", "netease", "wy"),
            MusicPlatform.MG to listOf("咪咕", "migu", "mg"),
            MusicPlatform.LOCAL to listOf("本地", "local")
        )

        for ((platform, keywords) in platformKeywords) {
            if (keywords.any { scriptContent.contains(it, ignoreCase = true) }) {
                platforms[platform] = SourcePlatformInfo(
                    name = platform.displayName,
                    actions = if (platform == MusicPlatform.LOCAL) {
                        listOf("musicUrl", "lyric", "pic")
                    } else {
                        listOf("musicUrl")
                    },
                    qualitys = AudioQuality.entries.toList()
                )
            }
        }

        return platforms
    }

    /**
     * 验证脚本格式
     * 兼容洛雪官方/第三方音源，包括混淆后的音源：
     * - 宿主 API 可能是点号写法 globalThis.lx，也可能是方括号写法 globalThis['lx']；
     * - 注册调用可能是字面量 send(...) / EVENT_NAMES.inited，也可能在混淆后被包装（send 作为实参传入、EVENT_NAMES 用计算下标访问）。
     */
    private fun validateScript(scriptContent: String): Boolean {
        // 1. 必须有 @name 元数据（洛雪音源标识）
        if (!scriptContent.contains("@name")) return false

        // 2. 必须引用洛雪宿主 API 对象 globalThis.lx（兼容点号与方括号两种写法）
        val referencesLxApi = scriptContent.contains("globalThis.lx")
            || scriptContent.contains("globalThis['lx']")
            || scriptContent.contains("globalThis[\"lx\"]")
        if (!referencesLxApi) return false

        // 3. 必须注册音源：调用 send(...) 或通过 EVENT_NAMES 监听 inited 事件。
        //    兼容混淆源（send 经包装函数调用、EVENT_NAMES 用计算下标访问，无字面量 send( / EVENT_NAMES.inited）。
        val registersSource = scriptContent.contains("send(")
            || scriptContent.contains("EVENT_NAMES.inited")
            || (scriptContent.contains("send") && scriptContent.contains("EVENT_NAMES"))
        if (!registersSource) return false

        return true
    }
}

/**
 * 脚本元数据
 */
data class ScriptMetadata(
    val name: String,
    val description: String?,
    val version: String?,
    val author: String?,
    val homepage: String?
)

/**
 * 解析结果
 */
sealed class ParseResult {
    data class Success(
        val metadata: ScriptMetadata,
        val platforms: Map<MusicPlatform, SourcePlatformInfo>,
        val scriptContent: String
    ) : ParseResult()

    data class Error(val message: String) : ParseResult()
}