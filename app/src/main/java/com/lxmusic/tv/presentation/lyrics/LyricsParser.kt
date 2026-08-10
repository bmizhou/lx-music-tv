package com.lxmusic.tv.presentation.lyrics

import java.util.regex.Pattern

/**
 * 歌词解析器
 * 解析LRC格式的歌词文件
 */
class LyricsParser {
    
    companion object {
        // LRC时间标签正则表达式
        private val LRC_TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.?(\\d{0,3})\\]")

        // LRC元数据标签
        private val LRC_METADATA_PATTERN = Pattern.compile("\\[(\\w+):(.+?)\\]")

        /**
         * 格式化时间显示
         * @param timeMs 时间（毫秒）
         * @return 格式化的时间字符串（mm:ss）
         */
        fun formatTime(timeMs: Long): String {
            val minutes = (timeMs / 1000) / 60
            val seconds = (timeMs / 1000) % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    /**
     * 解析LRC歌词
     * @param lrcContent LRC歌词内容
     * @return 解析后的歌词对象
     */
    fun parse(lrcContent: String): Lyrics {
        val lines = mutableListOf<LyricLine>()
        val metadata = mutableMapOf<String, String>()
        
        lrcContent.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach
            
            // 尝试解析元数据标签
            val metadataMatcher = LRC_METADATA_PATTERN.matcher(trimmedLine)
            if (metadataMatcher.matches()) {
                val key = metadataMatcher.group(1) ?: ""
                val value = metadataMatcher.group(2) ?: ""
                metadata[key] = value
                return@forEach
            }
            
            // 尝试解析时间标签和歌词内容
            val timeMatcher = LRC_TIME_PATTERN.matcher(trimmedLine)
            val times = mutableListOf<Long>()
            
            while (timeMatcher.find()) {
                val minutes = timeMatcher.group(1)?.toLongOrNull() ?: 0
                val seconds = timeMatcher.group(2)?.toLongOrNull() ?: 0
                val milliseconds = timeMatcher.group(3)?.toLongOrNull() ?: 0
                
                // 补全毫秒位数
                val ms = when {
                    timeMatcher.group(3)?.length == 1 -> milliseconds * 100
                    timeMatcher.group(3)?.length == 2 -> milliseconds * 10
                    else -> milliseconds
                }
                
                val totalMs = minutes * 60 * 1000 + seconds * 1000 + ms
                times.add(totalMs)
            }
            
            // 提取歌词内容（移除所有时间标签）
            val content = trimmedLine.replace(LRC_TIME_PATTERN.toRegex(), "").trim()
            
            // 为每个时间标签创建歌词行
            times.forEach { time ->
                lines.add(LyricLine(timeMs = time, content = content))
            }
        }
        
        // 按时间排序
        lines.sortBy { it.timeMs }
        
        return Lyrics(
            metadata = metadata,
            lines = lines
        )
    }
    
    /**
     * 解析带翻译的歌词
     * @param lrcContent 主歌词内容
     * @param translationContent 翻译歌词内容
     * @return 解析后的歌词对象
     */
    fun parseWithTranslation(
        lrcContent: String,
        translationContent: String
    ): Lyrics {
        val mainLyrics = parse(lrcContent)
        val translationLyrics = parse(translationContent)
        
        // 创建翻译映射
        val translationMap = mutableMapOf<Long, String>()
        translationLyrics.lines.forEach { line ->
            translationMap[line.timeMs] = line.content
        }
        
        // 合并歌词和翻译
        val mergedLines = mainLyrics.lines.map { line ->
            line.copy(translation = translationMap[line.timeMs])
        }
        
        return mainLyrics.copy(lines = mergedLines)
    }
    
    /**
     * 查找当前播放位置对应的歌词行
     * @param lyrics 歌词对象
     * @param currentPositionMs 当前播放位置（毫秒）
     * @return 当前歌词行索引
     */
    fun findCurrentLineIndex(lyrics: Lyrics, currentPositionMs: Long): Int {
        if (lyrics.lines.isEmpty()) return -1
        
        // 二分查找
        var left = 0
        var right = lyrics.lines.size - 1
        var result = 0
        
        while (left <= right) {
            val mid = (left + right) / 2
            if (lyrics.lines[mid].timeMs <= currentPositionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }
        
        return result
    }
    
    /**
     * 获取下一行歌词
     * @param lyrics 歌词对象
     * @param currentLineIndex 当前行索引
     * @return 下一行歌词索引，如果没有下一行则返回-1
     */
    fun getNextLineIndex(lyrics: Lyrics, currentLineIndex: Int): Int {
        if (currentLineIndex < 0 || currentLineIndex >= lyrics.lines.size - 1) {
            return -1
        }
        return currentLineIndex + 1
    }
    
    /**
     * 获取上一行歌词
     * @param lyrics 歌词对象
     * @param currentLineIndex 当前行索引
     * @return 上一行歌词索引，如果没有上一行则返回-1
     */
    fun getPreviousLineIndex(lyrics: Lyrics, currentLineIndex: Int): Int {
        if (currentLineIndex <= 0) {
            return -1
        }
        return currentLineIndex - 1
    }
}

/**
 * 歌词数据类
 */
data class Lyrics(
    val metadata: Map<String, String> = emptyMap(),
    val lines: List<LyricLine> = emptyList()
) {
    /**
     * 获取歌曲标题
     */
    val title: String?
        get() = metadata["ti"]
    
    /**
     * 获取艺术家
     */
    val artist: String?
        get() = metadata["ar"]
    
    /**
     * 获取专辑
     */
    val album: String?
        get() = metadata["al"]
    
    /**
     * 获取歌词作者
     */
    val author: String?
        get() = metadata["by"]
    
    /**
     * 获取偏移量
     */
    val offset: Long
        get() = metadata["offset"]?.toLongOrNull() ?: 0
    
    /**
     * 检查是否有歌词
     */
    val hasLyrics: Boolean
        get() = lines.isNotEmpty()
}

/**
 * 歌词行数据类
 */
data class LyricLine(
    val timeMs: Long,
    val content: String,
    val translation: String? = null
) {
    /**
     * 获取格式化的时间
     */
    val formattedTime: String
        get() = LyricsParser.formatTime(timeMs)
    
    /**
     * 检查是否有翻译
     */
    val hasTranslation: Boolean
        get() = translation != null && translation.isNotBlank()
}
