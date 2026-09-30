package com.tvmusic.constants

/**
 * 插件能力方法名。
 *
 * 背景：MusicFree 插件协议的方法名在项目里以裸字符串出现于 8 处
 * （PlayerManager 换源搜索/取词、HomeViewModel 探测、TopListViewModel、RecommendViewModel、
 * SheetViewModel 详情回退链、RemoteConfigService 能力探测/板块/详情回退/搜索共 14 处）。
 * 任何一个方法名拼错都是运行时才暴露的插件能力缺失，因此集中为常量。
 */
object PluginMethod {
    /** 搜索：args = [query, page, type("music")] */
    const val SEARCH = "search"

    /** 排行榜列表：返回 [{title, data:[board]}] 分组结构。 */
    const val TOP_LISTS = "getTopLists"

    /** 排行榜详情：args = [boardJson, page]。 */
    const val TOP_LIST_DETAIL = "getTopListDetail"

    /** 推荐歌单标签：返回 {pinned:[tag], data:[{title,data:[tag]}]}。 */
    const val RECOMMEND_TAGS = "getRecommendSheetTags"

    /** 推荐歌单分页：args = [tagJson, page]，返回 {data:[sheet], isEnd}。 */
    const val RECOMMEND_SHEETS = "getRecommendSheetsByTag"

    /** 歌单详情：args = [sheetJson, page]，返回 {data:[music], isEnd}。 */
    const val MUSIC_SHEET_INFO = "getMusicSheetInfo"

    /** 专辑详情：args = [albumJson, page]。 */
    const val ALBUM_INFO = "getAlbumInfo"

    /** 歌手作品：args = [artistJson, page, type("music")]。 */
    const val ARTIST_WORKS = "getArtistWorks"

    /** 导入外链歌单：args = [[url...]]，仅第 1 页有效。 */
    const val IMPORT_MUSIC_SHEET = "importMusicSheet"

    /** 取播放地址（老协议名，PlayerManager 换源/解析主源走它）。args = [trackJson, quality?]。 */
    const val MEDIA_SOURCE = "getMediaSource"

    /** 取播放地址（MusicFree 新协议名）。args = [trackJson]。 */
    const val MUSIC_URL = "getMusicUrl"

    /** 取歌词：args = [trackJson]，返回 {lyric, tlyric, lxlyric}。 */
    const val LYRIC = "getLyric"
}

/**
 * 媒体类型/条目 kind 常量。
 * 详情页与远程管理页都用这些字符串区分「点开歌单还是榜单/专辑/歌手」。
 */
object MediaKind {
    /** 用户歌单（可 importMusicSheet 兜底）。 */
    const val SHEET = "SHEET"

    /** 排行榜条目。 */
    const val TOPLIST = "TOPLIST"

    /** 专辑。 */
    const val ALBUM = "ALBUM"

    /** 歌手。 */
    const val ARTIST = "ARTIST"

    /** 外链导入歌单。 */
    const val IMPORT = "IMPORT"

    /** 搜索结果的普通歌曲行。 */
    const val MUSIC = "music"

    /** 搜索/插件返回的「歌单」卡片条目（区别于 MediaKind.SHEET 的 kind 字段值）。 */
    const val LIST = "list"

    /** 详情来源标注：歌单详情。 */
    const val SRC_MUSIC_SHEET_INFO = "musicSheetInfo"

    /** 详情来源标注：榜单详情。 */
    const val SRC_TOP_LIST_DETAIL = "topListDetail"

    /** 详情来源标注：专辑。 */
    const val SRC_ALBUM = "album"

    /** 详情来源标注：歌手。 */
    const val SRC_ARTIST = "artist"
}

/** 插件返回的 JSON 字段名。各家音源字段名不统一，按优先级传进 utils 的 firstStr/firstInt。 */
object JsonKey {
    const val TITLE = "title"
    const val NAME = "name"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val ID = "id"
    const val URL = "url"
    const val PIC = "pic"
    const val COVER = "cover"
    const val COVER_IMG = "coverImg"
    const val ARTWORK = "artwork"
    const val ARTWORK_URL = "artworkUrl"
    const val ALBUM_PIC = "albumPic"
    const val IMAGE = "image"
    const val IMG = "img"
    const val LOGO = "logo"
    const val AVATAR = "avatar"
    const val DATA = "data"
    const val IS_END = "isEnd"
    const val TOTAL = "total"
    const val PLATFORM = "platform"
    const val DESCRIPTION = "description"
    const val DESC = "desc"
    const val LYRIC = "lyric"
    const val PRIMARY_KEY = "primaryKey"
    const val DURATION = "duration"
    const val TIME = "time"
    const val PIC_URL = "picUrl"
    const val ALBUM_IMG = "albumImg"
}

/**
 * 封面字段的候选优先级（第一段）。
 * 覆盖绝大多数音源；取不到时再走 [ARTWORK_KEYS_FALLBACK] 第二段。
 * 原先 SearchEntry/SheetEntry/TopListEntry 各写了一份 2~7 级 ifBlank 链且字段集合不同，
 * 现统一为这两段候选表。
 */
val ARTWORK_KEYS = arrayOf(
    JsonKey.ARTWORK,
    JsonKey.COVER,
    JsonKey.COVER_IMG,
    JsonKey.PIC
)

/** 封面字段候选（第二段）：较老/较冷门的字段名。 */
val ARTWORK_KEYS_FALLBACK = arrayOf(
    JsonKey.ARTWORK_URL,
    JsonKey.ALBUM_PIC,
    JsonKey.IMAGE,
    JsonKey.IMG,
    JsonKey.LOGO,
    JsonKey.AVATAR
)

/** 标题字段的候选优先级顺序。 */
val TITLE_KEYS = arrayOf(JsonKey.TITLE, JsonKey.NAME)
