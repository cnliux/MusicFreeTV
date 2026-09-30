package com.tvmusic.utils

/**
 * 字符串清洗/规范化工具。
 *
 * 背景：插件返回的文本在远程管理页与 TV 端都会直接上屏，历史上有两处独立实现
 * （RemoteConfigService.cleanText 与各处零散的 replace 链），这里收口为一份。
 */

/**
 * 清洗插件返回的展示文本：
 *  - 去掉 U+FFFD 替换符（插件截断多字节字符产生的乱码方块）
 *  - 去掉 C0/C1 控制字符（部分音源把协议头/零宽字符混进标题）
 *  - 折叠连续空白为一个空格并去首尾
 */
fun cleanText(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    val sb = StringBuilder(s.length)
    var lastSpace = true
    for (ch in s) {
        val keep = when {
            ch == '\uFFFD' -> false
            ch.code < 0x20 && ch != '\n' -> false
            ch.code in 0x7F..0x9F -> false
            ch == ' ' || ch == '\t' || ch == '\n' -> {
                if (lastSpace) false else { sb.append(' '); lastSpace = true; false }
            }
            else -> true
        }
        if (keep) { sb.append(ch); lastSpace = false }
    }
    return sb.toString().trim()
}

/**
 * 歌手名净化：部分音源把「歌手 · 专辑」（或 歌手/组合）拼进 artist，
 * 取首个分隔段更利于跨源匹配（与 LrcApi 兜底查询同规则）。
 */
fun cleanArtist(artist: String?): String =
    (artist ?: "").split('·', '/', '／').first().trim()

/**
 * 曲目匹配键：`title \u0000 artist`（原样拼接，不做大小写/标点归一）。
 *
 * 用 \u0000 分隔是因为歌名/歌手里不可能出现该字符，拼接键天然无歧义。
 * 注意：这里**故意不归一化**——远程管理的收藏态标记（favMarks）历史就用这个
 * 原样键比对入库的收藏条目，若改成小写+去标点，老收藏数据会集体判为未收藏。
 */
fun matchKeyOf(title: String?, artist: String?): String =
    (title ?: "") + "\u0000" + (artist ?: "")
