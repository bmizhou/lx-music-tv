package com.lxmusic.tv.data.source

import android.util.Log
import com.lxmusic.tv.data.model.MusicPlatform
import com.lxmusic.tv.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 搜索联想引擎
 *
 * 参考 lxserver/blbl 的联想实现思路：输入变化时调用联想接口，返回联想词列表。
 * 各平台使用各自联想接口，便于对比差异：
 * - 网易云：music.163.com/api/search/suggest/web（s=关键词，返回 result.songs/artists/albums[].name，无需加密）
 * - 其余平台（QQ/酷狗/酷我/咪咕等）：统一走 QQ 音乐 smartbox_new.fcg（实测支持拼音，ZJL→周杰伦）
 *
 * 接口失败时回退到本地内置词库（歌手/歌曲 + 拼音首字母），保证 ZJL→周杰伦 一定可用。
 */
class SearchSuggestEngine(
    private val httpClient: HttpClient = HttpClient()
) {
    companion object {
        private const val TAG = "SearchSuggestEngine"

        /**
         * 内置兜底词库：中文名 + 拼音首字母（小写）
         * 接口失败或输入为拼音缩写时用于本地匹配
         */
        private val LOCAL_KEYWORDS = listOf(
            // 歌手
            Keyword("周杰伦", "zjl"), Keyword("林俊杰", "ljj"), Keyword("邓紫棋", "dzq"),
            Keyword("陈奕迅", "cyx"), Keyword("薛之谦", "xzq"), Keyword("王菲", "wf"),
            Keyword("张学友", "zxy"), Keyword("刘德华", "ldh"), Keyword("五月天", "wyt"),
            Keyword("周深", "zs"), Keyword("毛不易", "mby"), Keyword("李荣浩", "lrh"),
            Keyword("许嵩", "xs"), Keyword("汪苏泷", "wsl"), Keyword("张杰", "zj"),
            Keyword("李宇春", "lyc"), Keyword("张靓颖", "zly"), Keyword("华晨宇", "hcy"),
            Keyword("王力宏", "wlh"), Keyword("陶喆", "tz"), Keyword("蔡依林", "cyl"),
            Keyword("孙燕姿", "syz"), Keyword("梁静茹", "ljr"), Keyword("林忆莲", "lyl"),
            Keyword("杨千嬅", "yqh"), Keyword("容祖儿", "rze"), Keyword("Twins", "twins"),
            Keyword("Beyond", "beyond"), Keyword("凤凰传奇", "fhcq"), Keyword("刀郎", "dl"),
            Keyword("汪峰", "wf"), Keyword("朴树", "ps"), Keyword("许巍", "xw"),
            Keyword("郑钧", "zj"), Keyword("赵雷", "zl"), Keyword("陈粒", "cl"),
            Keyword("田馥甄", "thz"), Keyword("林宥嘉", "lyj"), Keyword("萧敬腾", "xjt"),
            Keyword("张惠妹", "zhm"), Keyword("王心凌", "wxl"), Keyword("杨丞琳", "ycl"),
            Keyword("张韶涵", "zsh"), Keyword("范玮琪", "fwq"), Keyword("黄义达", "hyd"),
            Keyword("胡夏", "hx"), Keyword("郁可唯", "ykw"), Keyword("袁娅维", "yyw"),
            Keyword("谭维维", "tww"), Keyword("吉克隽逸", "jkjy"), Keyword("吴青峰", "wqf"),
            Keyword("苏打绿", "sdl"), Keyword("告五人", "gwr"), Keyword("房东的猫", "fddm"),
            Keyword("陈鸿宇", "chy"), Keyword("马頔", "md"), Keyword("宋冬野", "sdy"),
            Keyword("李志", "lz"), Keyword("郭顶", "gd"), Keyword("徐佳莹", "xjy"),
            Keyword("戴佩妮", "dpn"), Keyword("蔡健雅", "cjy"), Keyword("莫文蔚", "mww"),
            Keyword("陈慧娴", "chx"), Keyword("王杰", "wj"), Keyword("齐秦", "qq"),
            Keyword("罗大佑", "ldy"), Keyword("李宗盛", "lzs"), Keyword("周华健", "zhj"),
            Keyword("任贤齐", "rxq"), Keyword("谢霆锋", "xtf"), Keyword("古巨基", "gjj"),
            Keyword("杨宗纬", "yzw"), Keyword("品冠", "pg"),
            Keyword("光良", "gl"), Keyword("阿杜", "ad"), Keyword("游鸿明", "yhm"),
            Keyword("郑源", "zy"), Keyword("六哲", "lz"), Keyword("冷漠", "lm"),
            // 热门歌曲
            Keyword("晴天", "qt"), Keyword("七里香", "qlx"), Keyword("稻香", "dx"),
            Keyword("青花瓷", "qhc"), Keyword("简单爱", "jda"), Keyword("告白气球", "gbqq"),
            Keyword("演员", "yy"), Keyword("丑八怪", "cbg"), Keyword("消愁", "xc"),
            Keyword("像我这样的人", "xwzydr"), Keyword("海阔天空", "hktk"), Keyword("光辉岁月", "ghsy"),
            Keyword("真的爱你", "zdan"), Keyword("朋友", "py"), Keyword("吻别", "wb"),
            Keyword("一千个伤心的理由", "yqgxsdly"), Keyword("十年", "sn"), Keyword("浮夸", "fk"),
            Keyword("孤勇者", "gyz"), Keyword("起风了", "qfl"), Keyword("平凡之路", "pfzl"),
            Keyword("成都", "cd"), Keyword("南山南", "nsn"), Keyword("董小姐", "dxj"),
            Keyword("斑马斑马", "bmbm"), Keyword("安河桥", "ahq"), Keyword("春风十里", "cfsl"),
            Keyword("理想", "lx"), Keyword("南方姑娘", "nfgn"),
            Keyword("蓝莲花", "llh"), Keyword("曾经的你", "cjdn"), Keyword("怒放的生命", "nfdsm"),
            Keyword("存在", "cz"), Keyword("春天里", "ctl"), Keyword("小苹果", "xpg"),
            Keyword("最炫民族风", "zxmzf"), Keyword("荷塘月色", "htys"), Keyword("月亮之上", "ylzs"),
            Keyword("自由飞翔", "zyfx"), Keyword("狂浪", "kl"), Keyword("野狼disco", "yldisco"),
            Keyword("沙漠骆驼", "smlt"), Keyword("沙漠之鹰", "smzy"), Keyword("少年", "sn"),
            Keyword("少年之名", "snzm"), Keyword("光年之外", "gnzw"), Keyword("泡沫", "pm"),
            Keyword("倒数", "ds"), Keyword("句号", "jh"), Keyword("来自天堂的魔鬼", "lzttdmg"),
            Keyword("微微", "ww"), Keyword("万有引力", "wyyl"), Keyword("年少有为", "nsyw"),
            Keyword("不将就", "bjj"), Keyword("模特", "mt"), Keyword("李白", "lb"),
            Keyword("认真的雪", "rzdx"), Keyword("刚刚好", "ggh"),
            Keyword("绅士", "ss"), Keyword("一半", "yb"), Keyword("意外", "yw"),
            Keyword("同桌的你", "tzdn"),
            Keyword("那些年", "nxn"), Keyword("小幸运", "xxy"), Keyword("我的少女时代", "wdsnsd"),
            Keyword("匆匆那年", "ccnn"), Keyword("时间煮雨", "sjzy"), Keyword("知否知否", "zfzf"),
            Keyword("凉凉", "ll"), Keyword("三生三世", "ssss"), Keyword("年轮", "nl"),
            Keyword("大鱼", "dy"), Keyword("左手指月", "zszy"), Keyword("缘起", "yq"),
            Keyword("卡路里", "kll"), Keyword("燃烧我的卡路里", "rswdkl")
        )

        data class Keyword(val name: String, val pinyin: String)
    }

    /**
     * 获取搜索联想词列表
     * 全部平台统一使用网易云联想接口（fetchWySuggest）：
     * 实测网易云只需首字母即可联想（如 zjl→周杰伦），且比 QQ（需全拼字母）更准确，故所有平台共用。
     * 接口失败时回退本地词库匹配（拼音首字母/中文/英文）
     */
    suspend fun suggest(term: String, platform: MusicPlatform): List<String> = withContext(Dispatchers.IO) {
        val t = term.trim()
        if (t.isEmpty()) return@withContext emptyList<String>()

        // 1. 全部平台统一调用网易云联想接口
        val apiResult = try {
            fetchWySuggest(t)
        } catch (e: Exception) {
            Log.w(TAG, "网易云联想接口失败(${platform}): ${e.message}")
            emptyList<String>()
        }

        // 2. 本地词库匹配（拼音首字母 + 中文包含 + 英文忽略大小写）
        val local = matchLocal(t)

        // 合并：接口结果优先，本地词库补充去重，最多 10 条
        val merged = LinkedHashSet<String>()
        merged.addAll(apiResult)
        merged.addAll(local)
        merged.toList().take(10)
    }

    /**
     * 本地词库匹配：
     * - 输入为拼音首字母（如 zjl）→ 匹配 pinyin 前缀/包含
     * - 输入为中文（如 周）→ 匹配 name 包含
     * - 输入为英文（如 beyond）→ 匹配 name/pinyin 忽略大小写包含
     */
    private fun matchLocal(term: String): List<String> {
        val t = term.trim()
        if (t.isEmpty()) return emptyList()

        val lower = t.lowercase()
        val isAllAscii = t.all { it.code < 128 }
        val hasCjk = t.any { it.code in 0x4E00..0x9FFF }

        // 按匹配强度排序：前缀 > 包含
        val prefixMatches = mutableListOf<Pair<Keyword, Int>>() // Int 为权重
        val containsMatches = mutableListOf<Pair<Keyword, Int>>()

        for (kw in LOCAL_KEYWORDS) {
            val name = kw.name
            val py = kw.pinyin.lowercase()

            val nameLower = name.lowercase()
            when {
                // 中文输入：匹配汉字名包含
                hasCjk && name.contains(t) -> {
                    if (name.startsWith(t)) prefixMatches.add(kw to 100) else containsMatches.add(kw to 60)
                }
                // 英文输入：匹配英文名
                isAllAscii && nameLower.contains(lower) -> {
                    if (nameLower.startsWith(lower)) prefixMatches.add(kw to 90) else containsMatches.add(kw to 50)
                }
                // 拼音匹配：pinyin 前缀或包含
                py.contains(lower) -> {
                    if (py.startsWith(lower)) prefixMatches.add(kw to 80) else containsMatches.add(kw to 40)
                }
            }
        }

        // 按权重降序，最多 5 条本地结果
        return (prefixMatches.sortedByDescending { it.second } + containsMatches.sortedByDescending { it.second })
            .map { it.first.name }
            .distinct()
            .take(5)
    }

    /**
     * 网易云联想：music.163.com/api/search/suggest/web
     * 老接口（/api/ 而非 /weapi/），无需加密，仅需浏览器 UA + Referer。
     * 注意：参数名为 s=（用 keywords= 会报「参数错误」），返回 result.songs/artists/albums[].name。
     * 与 QQ 并列实现，便于对比两平台联想差异。
     */
    private suspend fun fetchWySuggest(term: String): List<String> {
        val url = "https://music.163.com/api/search/suggest/web" +
                "?csrf_token=&s=${URLEncoder.encode(term, "UTF-8")}"
        val response = httpClient.get(
            url,
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        )
        if (!response.isSuccess) return emptyList()
        val json = JSONObject(response.body)
        if (json.optInt("code", -1) != 200) return emptyList()
        val result = json.optJSONObject("result") ?: return emptyList()

        val out = LinkedHashSet<String>()
        // 歌手优先
        result.optJSONArray("artists")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.getJSONObject(i).optString("name", "").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        // 歌曲
        result.optJSONArray("songs")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.getJSONObject(i).optString("name", "").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        // 专辑
        result.optJSONArray("albums")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.getJSONObject(i).optString("name", "").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
        }
        return out.toList().take(8)
    }

}
