package com.tvmusic.data

/**
 * 数据模型已按领域迁至 `com.tvmusic.model`：
 *  - [com.tvmusic.model.PluginModels]：PluginInfo / UserVarDef / PluginRecord / SubscriptionRecord / UserVariable
 *  - [com.tvmusic.model.MediaModels]：[com.tvmusic.model.PluginEntry] / SearchEntry / SheetEntry / TopListEntry / RecommendTag / HomeSection
 *  - [com.tvmusic.model.FavModels]：FavList / DetailKind / DetailTarget / SheetTarget
 *
 * 本文件仅保留 typealias 作为过渡期兼容层：新代码请直接 import `com.tvmusic.model.*`。
 * 迁移动机：模型此前与 SharedPreferences/文件持久化代码混在 `data` 包里，
 * 导致 11 个 UI/VM 文件为了拿一个 data class 而 import 整个数据层。
 */
typealias PluginInfo = com.tvmusic.model.PluginInfo
typealias UserVarDef = com.tvmusic.model.UserVarDef
typealias PluginRecord = com.tvmusic.model.PluginRecord
typealias SubscriptionRecord = com.tvmusic.model.SubscriptionRecord
typealias UserVariable = com.tvmusic.model.UserVariable

typealias PluginEntry = com.tvmusic.model.PluginEntry
typealias SearchEntry = com.tvmusic.model.SearchEntry
typealias SheetEntry = com.tvmusic.model.SheetEntry
typealias TopListEntry = com.tvmusic.model.TopListEntry
typealias RecommendTag = com.tvmusic.model.RecommendTag
typealias HomeSection = com.tvmusic.model.HomeSection

typealias FavList = com.tvmusic.model.FavList
typealias DetailKind = com.tvmusic.model.DetailKind
typealias DetailTarget = com.tvmusic.model.DetailTarget
