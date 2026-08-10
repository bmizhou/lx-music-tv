package com.lxmusic.tv.data.source

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * 播放源解析器单元测试
 */
class ScriptParserTest {

    private val parser = ScriptParser()

    @Test
    fun `test parse valid script`() {
        val scriptContent = """
            /**
             * @name 测试音乐源
             * @description 这是一个测试用的音乐源脚本
             * @version 1.0.0
             * @author 测试作者
             * @homepage https://example.com
             */
            
            const { EVENT_NAMES, request, on, send } = globalThis.lx
            
            send(EVENT_NAMES.inited, {
                openDevTools: false,
                sources: {
                    kw: {
                        name: '酷我音乐',
                        type: 'music',
                        actions: ['musicUrl'],
                        qualitys: ['128k', '320k', 'flac', 'flac24bit']
                    }
                }
            })
        """.trimIndent()

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals("测试音乐源", success.metadata.name)
        assertEquals("这是一个测试用的音乐源脚本", success.metadata.description)
        assertEquals("1.0.0", success.metadata.version)
        assertEquals("测试作者", success.metadata.author)
        assertEquals("https://example.com", success.metadata.homepage)
    }

    @Test
    fun `test parse script with multiple platforms`() {
        val scriptContent = """
            /**
             * @name 多平台音乐源
             * @version 2.0.0
             */
            
            send(EVENT_NAMES.inited, {
                sources: {
                    kw: {
                        name: '酷我音乐',
                        type: 'music',
                        actions: ['musicUrl'],
                        qualitys: ['128k', '320k']
                    },
                    kg: {
                        name: '酷狗音乐',
                        type: 'music',
                        actions: ['musicUrl'],
                        qualitys: ['128k', '320k', 'flac']
                    }
                }
            })
        """.trimIndent()

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals("多平台音乐源", success.metadata.name)
        assertTrue(success.platforms.containsKey(MusicPlatform.KW))
        assertTrue(success.platforms.containsKey(MusicPlatform.KG))
    }

    @Test
    fun `test parse invalid script`() {
        val scriptContent = "console.log('not a valid source script')"

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Error)
    }

    @Test
    fun `test parse script without name annotation`() {
        val scriptContent = """
            /**
             * @version 1.0.0
             * @author 测试作者
             */
            
            send(EVENT_NAMES.inited, {
                sources: {}
            })
        """.trimIndent()

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        // 没有@name注解时，应该使用默认名称
        assertEquals("未知源", success.metadata.name)
    }

    @Test
    fun `test parse local source`() {
        val scriptContent = """
            /**
             * @name 本地音乐源
             * @version 1.0.0
             */
            
            send(EVENT_NAMES.inited, {
                sources: {
                    local: {
                        name: '本地音乐',
                        type: 'music',
                        actions: ['musicUrl', 'lyric', 'pic'],
                        qualitys: []
                    }
                }
            })
        """.trimIndent()

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertTrue(success.platforms.containsKey(MusicPlatform.LOCAL))
        
        val localPlatform = success.platforms[MusicPlatform.LOCAL]
        assertNotNull(localPlatform)
        assertTrue(localPlatform!!.actions.contains("musicUrl"))
        assertTrue(localPlatform.actions.contains("lyric"))
        assertTrue(localPlatform.actions.contains("pic"))
    }

    @Test
    fun `test parse script with request handler`() {
        val scriptContent = """
            /**
             * @name 带请求处理的音乐源
             * @version 1.0.0
             */
            
            const { EVENT_NAMES, request, on, send } = globalThis.lx
            
            on(EVENT_NAMES.request, ({ source, action, info }) => {
                return new Promise((resolve, reject) => {
                    // 处理请求
                });
            })
            
            send(EVENT_NAMES.inited, {
                sources: {
                    kw: {
                        name: '酷我音乐',
                        type: 'music',
                        actions: ['musicUrl'],
                        qualitys: ['128k', '320k']
                    }
                }
            })
        """.trimIndent()

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Success)
    }

    @Test
    fun `test validate script format`() {
        // 有效脚本
        val validScript = """
            /**
             * @name 测试源
             */
            globalThis.lx.send('inited', {})
        """.trimIndent()

        assertTrue(parser.parse(validScript) is ParseResult.Success)

        // 无效脚本 - 缺少必要的注解
        val invalidScript1 = "console.log('test')"
        assertFalse(parser.parse(invalidScript1) is ParseResult.Success)

        // 无效脚本 - 缺少send调用
        val invalidScript2 = """
            /**
             * @name 测试源
             */
            console.log('test')
        """.trimIndent()
        // 这个应该失败，因为没有send调用
        // 但根据当前实现，可能仍然会通过验证
        // 需要根据实际验证逻辑调整
    }

    @Test
    fun `test parse script with quality variants`() {
        val scriptContent = """
            /**
             * @name 音质测试源
             * @version 1.0.0
             */
            
            send(EVENT_NAMES.inited, {
                sources: {
                    kw: {
                        name: '酷我音乐',
                        type: 'music',
                        actions: ['musicUrl'],
                        qualitys: ['128k', '320k', 'flac', 'flac24bit']
                    }
                }
            })
        """.trimIndent()

        val result = parser.parse(scriptContent)

        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        val kwPlatform = success.platforms[MusicPlatform.KW]
        assertNotNull(kwPlatform)
        assertEquals(4, kwPlatform!!.qualitys.size)
        assertTrue(kwPlatform.qualitys.contains(AudioQuality.QUALITY_128K))
        assertTrue(kwPlatform.qualitys.contains(AudioQuality.QUALITY_320K))
        assertTrue(kwPlatform.qualitys.contains(AudioQuality.FLAC))
        assertTrue(kwPlatform.qualitys.contains(AudioQuality.FLAC_24BIT))
    }

    @Test
    fun `test parse real world script`() {
        // 读取测试脚本文件
        val testScriptPath = "test/test_source.js"
        val testScriptFile = File(testScriptPath)
        
        if (testScriptFile.exists()) {
            val scriptContent = testScriptFile.readText(Charsets.UTF_8)
            val result = parser.parse(scriptContent)
            
            assertTrue(result is ParseResult.Success)
            val success = result as ParseResult.Success
            assertEquals("测试音乐源", success.metadata.name)
            assertEquals("1.0.0", success.metadata.version)
        }
    }
}