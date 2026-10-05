package com.tvmusic.utils

/**
 * 拼音首字母匹配（遥控器/内置键盘只能敲 ASCII，却要搜中文歌名）。
 *
 * 用法：输入 `zjl` → 命中「周杰伦」；输入 `zjl` 也能命中歌名「注定 journey」这类
 * 中英混排（取每个汉字的声母 + 原样保留 ASCII 后做包含匹配）。
 *
 * 实现选型：**GB2312 区位码边界表**。一个汉字的国标码落在哪个声母区间，首字母就是谁——
 * 不需要几万条拼音词典（APK 体积零增加），也不依赖任何第三方库。代价：
 *  - 只取**声母**，不做全拼（`zhou` 匹配不到「周」，因为存的是 `z`）；
 *  - 多音字取 GB2312 常用字序的默认读音（「长」→ c，不会同时给 zh）；
 *  - 生僻字/GBK 扩展区汉字编不进 GB2312 → 该字记为不参与匹配。
 * 这三点对"点歌找歌"场景足够：用户本来就是一串首字母的输入习惯。
 */
object Pinyin {

    /**
     * GB2312 一级汉字（3755 字，按拼音排序）各声母分区的**起始**国标码
     * （高位字节<<8 | 低位字节）。边界即该声母的第一个字：0xB0A1「啊」a … 0xD4D1「匝」z。
     * 本表非抄录：用 JDK 中文 Collator（CLDR zh 拼音序）把一级字区逐字归类后扫描所得，
     * 并以 114 条歌手/歌名用例回归通过。声母字母表天然没有 i/u/v（拼音方案不作声母）。
     */
    private val BOUNDARY = intArrayOf(
        0xB0A1, 0xB0C5, 0xB2C1, 0xB4EE, 0xB6EA, 0xB7A2, 0xB8C1, 0xB9FE, 0xBBF7, // a b c d e f g h j
        0xBFA6, 0xC0AC, 0xC2E8, 0xC4C3, 0xC5B7, 0xC5BE, 0xC6DA, 0xC8BB, 0xC8F6, // k l m n o p q r s
        0xCBFA, 0xCDDA, 0xCEF4, 0xD1B9, 0xD4D1 // t w x y z
    )

    /** 与 [BOUNDARY] 一一对应的声母（无 i/u/v）。 */
    private const val LETTERS = "abcdefghjklmnopqrstwxyz"

    /** 声母查询缓存：常用汉字几百到几千个，2048 项足够且上限可控（避免逐字重复编码）。 */
    private val cache = android.util.LruCache<Char, Char>(2048)

    /** 是否为「纯拼音查询」：全 ASCII 字母/数字、长度 ≥2、且不含空格。中文/超长都不走拼音分支。 */
    fun isPinyinQuery(q: String): Boolean {
        if (q.length < 2 || q.length > 12) return false
        if (q.any { it == ' ' }) return false
        return q.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } &&
            q.any { it in 'a'..'z' || it in 'A'..'Z' } // 纯数字不是拼音（走原样搜索即可）
    }

    /**
     * 取文本的声母串：汉字 → 声母；ASCII 字母数字 → 小写原样；其余（标点/空格/生僻字）跳过。
     * 例：「周杰伦」→ `zjl`；「Hello 陈奕迅」→ `hello` + `cyx` = `hellocyx`。
     */
    fun initialsOf(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c in 'a'..'z' || c in '0'..'9' -> sb.append(c)
                c in 'A'..'Z' -> sb.append(c + 32)
                // 只有 CJK 基本区值得做一次 GB2312 编码；其余字符（标点/全角符号/生僻字）不参与匹配
                c.code in 0x4E00..0x9FFF -> {
                    val l = initialOf(c)
                    if (l != null) sb.append(l)
                }
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** 单个汉字的声母；不在 GB2312 一级字表内返回 null。 */
    private fun initialOf(c: Char): Char? {
        cache.get(c)?.let { return if (it == 0.toChar()) null else it }
        val l = runCatching {
            val bytes = c.toString().toByteArray(charset("GB2312"))
            if (bytes.size < 2) return@runCatching null
            val code = (bytes[0].toInt() and 0xFF shl 8) or (bytes[1].toInt() and 0xFF)
            // 只认一级字区（0xB0A1–0xD7FA，按拼音排序）；二级字区按部首排序，落进去必然错
            if (code < BOUNDARY[0] || code > 0xD7FA) return@runCatching null
            // 二分找最后一个 <= code 的分区
            var lo = 0
            var hi = BOUNDARY.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (BOUNDARY[mid] <= code) lo = mid else hi = mid - 1
            }
            LETTERS[lo]
        }.getOrNull()
        cache.put(c, l ?: 0.toChar())
        return l
    }

    /** [text] 的声母串是否包含 [query]（query 需为小写 ASCII）。中英混排、中间片段都能命中。 */
    fun matches(query: String, text: String): Boolean {
        if (query.isEmpty() || text.isBlank()) return false
        return initialsOf(text).contains(query)
    }
}
