# MusicFreeTV 技术文档

本文档记录 MusicFreeTV 的内部实现细节：目录结构、JS 引擎与插件协议、播放器、远程 Web 控制台与 HTTP API。

面向普通使用者的介绍与构建请见 [README.md](README.md)。

---

## 目录结构

```text
MusicFreeTV/
├── app/                                              # Android TV 应用模块
│   ├── build.gradle.kts                              # 签名/构建配置 + ABIs
│   └── src/main/
│       ├── AndroidManifest.xml                       # Activity + PlaybackService + 权限
│       ├── cpp/js_job_pump.c                         # 手动推进 QuickJS Promise/await 微任务队列
│       ├── java/com/tvmusic/
│       │   ├── MainActivity.kt                       # Compose 入口 + ModalNavigation + 焦点/顶栏/导航
│       │   ├── core/TvMusicApp.kt                    # Application：初始化 Store/Runtime/Repository/Player
│       │   ├── data/                                 # Models.kt + PluginStore.kt（SQLite）
│       │   ├── runtime/                              # JsEngine 接口 + QuickJsEngine（quickjs-android + native job pump + 防御性原生桥）
│       │   ├── plugin/                               # PluginRuntime（粘性路由并行引擎池，3→5 动态扩容）+ PluginRepository（订阅/安装/备份/探测 platform）
│       │   ├── config/                               # SearchSettings + IdleSettings + MetaSettings（lrc.cx 兜底 / 封面形状转速）
│       │   ├── player/                               # PlayerManager + PlaybackService（Media3 + MediaSessionService）
│       │   ├── remote/                               # RemoteConfigService（局域网 HTTP 服务 + Web 控制台，端口 9527）
│       │   └─ ui/
│       │       ├─ theme/Theme.kt + LyricSettings.kt  # 深色 Material3 配色 + 歌词显示配置
│       │       ├─ components/Components.kt           # AppTitleBar / MediaCard / MusicRow / tvFocus / ModalCard 等共享件
│       │       ├─ components/QrImage.kt              # zxing 生成远程管理地址二维码
│       │       ├─ about/AboutScreen                  # 关于：开源声明 + 检查更新（GitHub Release + CDN 多线路择优）
│       │       ├─ home/HomeScreen+ViewModel           # 首页：推荐歌单 + 排行榜
│       │       ├─ search/SearchScreen+ViewModel       # 搜索：多音源并行 + 边搜边出 + 过滤排序
│       │       ├─ sheet/SheetScreen+ViewModel         # 歌单详情（musicList / getTopListDetail / importMusicSheet）
│       │       ├─ player/PlayerScreen                 # 全屏播放：封面 + 逐行歌词 + 进度 + 控制
│       │       ├─ setting/SettingsScreen+ViewModel    # 插件/订阅/用户变量/歌词/远程（地址）
│       │       └─ common/                             # Vms 工厂
│       └─ res/                                       # banner / 启动图标 / colors / themes / network_security_config
├── gradle/
│   ├─ libs.versions.toml                             # 依赖版本（quickjs / media3 / zxing 等）
│   └─ wrapper/gradle-wrapper.properties               # Gradle 8.10.2
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── README.md
└── TECHNICAL.md
```

---

## 1. JS 引擎与插件协议（QuickJS + 原生桥）

- **引擎选型**：[taoweiji/quickjs-android](https://github.com/taoweiji/quickjs-android)（支持 Event Queue、CommonJS、Java→JS 回调），并配套 `cpp/js_job_pump.c` 手动推进 Promise/await 微任务队列（否则插件 async 方法在首个 `await` 处永久挂起）。
- **async RPC**：JS 端 `__invoke(platform, method, argsJson, cbId)` → `Promise.then` → `nativeBridge.onPluginResult(cbId, json)` 回吐；Java 侧用 `CompletableFuture` + 轮询推进 job（超时抛 `PluginCallException`）。
- **并行搜索引擎池（粘性路由）**：单 QuickJS 引擎单线程、插件 HTTP 为同步阻塞桥，音源只能逐个搜。`PluginRuntime` 维护多台独立引擎（各自 JS 线程 + 独立 runtime，均注册全量插件），**同一平台固定绑定同一引擎**（粘性路由，保证 cookie/token 等模块级状态不分裂）；播放解析/歌词等关键调用走平台专属 home 引擎，与浏览流量隔离，避免被慢源搜索排队阻塞。引擎池默认 3 台，全部忙碌时**动态扩容至 5 台**（零预注册、按需注册目标插件）。
- **HTTP 原生桥（防御性绑定）**：原生能力以 `registerJavaMethod` 手动绑定 `__bridge_*` 全局函数，再由垫片组装为 `nativeBridge` 对象——**不使用 `addJavascriptInterface` 反射绑定**（其按 Java 方法签名严格校验参数个数，第三方插件错参调用会成为 JNI pending exception 触发 CheckJNI SIGABRT 整进程崩溃）。回调内对参数越界/类型不符一律取默认值并整体 try/catch，任何插件错参只记日志绝不向 JNI 抛异常。
- **nativeBridge.httpRequest** → OkHttp 同步请求；自动剥离插件传入的 `Accept-Encoding`，由 OkHttp 透明处理 gzip/br。
- **全局库暴露**：`bootstrap.js` 将 axios/dayjs/he/qs/cheerio/crypto-js/big-integer/webdav 暴露为全局，兼容「裸 `axios.get`」等直接引用库的插件。
- **Parcel 打包兼容**：`__registerPlugin` 自动识别 `module.exports.default` 与普通 CommonJS；平台名取源码**最后一次出现**的 `platform: "..."`（支持字面量与变量引用）。
- **方法缺失处理**：未实现的方法统一返回 `{ __notImplemented: true }`，UI 层静默跳过。

### 换源策略

`PlayerManager.play()` 把「跳过主源**解析**」与「跳过主源**结果**」严格区分：

- `skipPrimaryResolve` 只包含「主源已知有问题」两种情况：`forceFallback`（取流失败重试）与 `tooShortMeta`（元数据时长已低于阈值）。
- 「聚合搜索」（`preferPlugin == "*"`）**不影响**主源是否被解析，只作为换源时的排序偏好（`includeSelf = true`）。
- 是否进入换源路径由 `primaryUsable`（主源是否解析出非 FLV 的可用 URL）决定。
- `tooShortMeta` 时 `media == null` 但**不算** `primaryFailed`（主源根本没被解析过）。

### lrc.cx 兜底

- `withFallbackArtwork`：无封面时从 lrc.cx `/cover` 取图（部分歌 301 → Apple CDN，OkHttp 自动跟随）。
- 回退封面必须写进**整个队列** `effectiveQueue`，不能只写 `current`：`onMediaItemTransition` 会用 `queue[idx]` 覆盖 `current`，只写 `current` 会被打回 ♪。
- `fetchFallbackLyric`：来自 lrc.cx `/lyrics`。**坑**：Android 宽松的 `JSONArray(body)` 会把 `[Verse]` 开头的 LRC 解析成 `["Verse"]`（首元素非对象 → 提前 return 丢弃真实歌词）。已加固：仅当首元素是对象且带非空 `lyrics` 才按 JSON 处理，否则走 `parseLrc(body)`。
- lrc.cx 透明 gzip，OkHttp 自动解压，无需额外处理。

---

## 2. 插件加载与订阅管理

- **无内置默认订阅源**：不向 APK 写入任何来源地址。订阅源由用户自行添加（设置页 / Web 控制台 `/api/subscriptions`），或放置设备文件 `plugin_sources` 让应用读取。
- **内容识别安装**：`installContent` / `importFromUrl` / `syncOne` 会先判断内容——JSON `plugins` 列表则批量安装，否则按单个 `.js` 插件导入。因此 **.js 直链与 plugins.json 均可直接添加并同步**。
- **平台探测**：`PluginRepository.detectPlatform` 支持 `platform: "字面量"` 与 `const X = "..."` + `platform: X` 变量引用，并过滤 `pc / web / WebFilter / H5 / .json` 等干扰项，避免误装。
- **插件元信息**：通过 JS 运行时读取 `platform/version/author/srcUrl/userVariables/supportedSearchType` 存入 `PluginRecord.info`。

---

## 3. 播放器（Media3 ExoPlayer）

- **PlayerManager**：单例持有 ExoPlayer，对外暴露 `StateFlow<PlayerUiState>`（当前歌曲 / 播放状态 / 队列 / 歌词 / 进度）。
- **按需取流**：播放时调用插件 `getMediaSource(item, "standard")` → `{ url, headers }` → 写入 `DefaultHttpDataSource` 默认请求头（含 Referer 等，HLS 片段同样生效）。
- **队列管理**：歌单全部歌曲作为队列传入 `PlayerManager.play`，支持上/下一首（`skipTo` 重新 fetch URL）；下一首预加载缓存（最多 4 条 / 10 分钟有效）；系统均衡器 + 重低音（设置持久化）。
- **歌词**：`getLyric` 兼容 `{ rawLrc, translation }`、`lyricList`、`translationList`；播放页内嵌逐行歌词；歌词加载带代数计数器防快速切歌串词。
- **待机显示**：播放中一段时间无遥控器操作自动进入播放器页；时长与开关可在远程管理后台配置（默认 1 分钟）。

### 封面图层结构

`PlayerScreen` 的封面为四层嵌套，顺序不可随意调整：

1. **外层** `.size(300.dp).tvFocus(1.03f, circle = isCircle, shapeOverride = coverClipShape)`——焦点缩放必须在此层，**放在裁剪层之外**，否则描边被 `clip` 切掉。
2. **阴影层** `.graphicsLayer { shadowElevation = 26.dp }`。
3. **裁剪层** `.clip(coverClipShape)`，`coverClipShape` 为 `CircleShape` 或 `RoundedCornerShape(tokens.radius * 2)`（`isCircle` 由 `MetaSettings.coverShapeState` 驱动）。
4. **旋转层** `.graphicsLayer { rotationZ = if (spinEnabled && state.isPlaying) rotation else 0f }`——黑胶旋转独立成层，不受裁剪与阴影影响。`spinEnabled = coverSpinMs > 0`（**circle / square 都转**）。

**旋转实现（帧驱动，勿回退 rememberInfiniteTransition）**：`LaunchedEffect(coverSpinMs, coverSpinDir, spinEnabled, isPlaying)` + `withFrameNanos` 每帧推进 `mutableFloatStateOf` 角度（`dt / (msPerTurn*1e6) * 360 * spinDirFactor`，ccw 为 -1）。原因：`rememberInfiniteTransition` 的 animationSpec 只在**首次组合时固定**，远程改转速/方向后动画不重启——这就是「旋转耗时改了无效」的根因。帧驱动下暂停冻结（从当前角度继续）、时长/方向改动即时生效。方向持久化在 `MetaSettings.coverSpinDir`（cw/ccw，非法值忽略），`/api/meta` GET/POST 均带 `coverSpinDir`，远程管理封面设置卡有方向下拉。

> 变量命名注意：外层状态是 `val coverShape by ...collectAsState()`，局部形状变量必须叫 `coverClipShape`，否则 `Conflicting declarations`。

### 焦点描边跟随形状

`Components.kt` 的 `tvFocus(scaleOverride, circle, shapeOverride)`：

- 焦点描边圆角由 `DrawScope.focusCornerRadius(shapeOverride, circle, themeRadius, minDimension)` 推导：`circle = true` → 半径 = 短边/2（正圆）；`shapeOverride === RectangleShape` → `CornerRadius.Zero`（**直角焦点框**，纯文字按钮用）；否则取 `RoundedCornerShape.topStart` 的 Dp 圆角；再否则退回主题 `tokens.radius`。
- 该 helper 声明为 **`DrawScope` 扩展**以拿到 `Density` 做 `Dp.toPx()`；写成普通函数会报 `Unresolved reference 'toPx'`。
- helper 内**不要**对 `CircleShape` 做 `is` 判断（该符号在 `Components.kt` 中无法解析），圆形统一由 `circle: Boolean` 传入。
- 发光用 `drawIntoCanvas` + `nativeCanvas.drawRoundRect`；边框用 `drawRoundRect(style = Stroke)`，两者共用同一 `radius`。
- 焦点视觉全部由 Compose 自绘（主题化边框 + 发光），不依赖平台高亮；`themes.xml` 里 `android:defaultFocusHighlightEnabled=false` 仅 API 26+ 生效，Android 7 靠「焦点永不落在全屏节点上」根治白圈。
- **按钮纯文字化约定（2026-09-29，用户指令）**：全部按钮**无底色 / 无圆角 / 无阴影**——删 `.clip(...)` + `.background(...)`，焦点框传 `shapeOverride = RectangleShape`（直角），主操作按钮靠**文字 primary 色**（必要时 SemiBold）强调，不再靠填充底色。涉及组件见 AGENTS.md 同日章节。例外：颜色选择器（需选中反馈底色）、歌曲行/卡片（列表项非按钮）、封面阴影。焦点视觉（tvFocus 边框/发光）**必须保留**——TV 焦点红线。

### TV 焦点三层兜底

1. **根节点禁止 `.focusable()`**——全屏 focusable 节点 = 遥控器死区 + Android 7 白圈。
2. **焦点观察器**（MainActivity）：`rootContentFocus` + 200ms 轮询 `LaunchedEffect`，整树无焦点时把焦点送回 `LocalFocusFallback` 槽注册的目标。用 state 轮询而非事件回调（弹框关闭后有约 20ms 整树无焦点窗口，事件回调有顺序竞态）。
3. **兜底注册**：子页 = BackTopBar 返回按钮、播放页 = 主按钮、主页 = 当前页签（`keyed (fr, isSelected)`）；弹框全部显式 `initialFocus`。
   - `tvInitialFocus(fr)` 与 `tvFocusFallback(fr)` **必须共享同一 FocusRequester**（同节点双 fr 只有最后一个生效）。

---

## 4. 远程 Web 控制台（端口 9527）

TV 端启动后会在局域网内开启 HTTP 服务，手机 / PC 浏览器访问 `http://<电视盒子IP>:9527` 即可远程使用。**不提供 token / 认证机制**，依赖局域网物理隔离，请仅在可信局域网内启用。

### 页面一览

- **播放**：当前歌曲/封面/歌词、播放进度、循环模式切换，控制按钮直接作用于电视端 ExoPlayer。
- **搜索**：音源多选 chips（顺序按已保存的优先级）、时长过滤、必需封面、结果排序；搜索结果可单点播放或批量入队。
- **推荐**（原「插件」栏目）：与 APK 1:1 的两条链路——排行榜（`getTopLists` → 榜单详情）与推荐歌单（`getRecommendSheetTags` → `getRecommendSheetsByTag` → 歌单详情），并支持整张歌单收藏、单曲收藏/播放。
- **收藏**：查看/切换收藏专辑、新建/重命名/删除收藏夹、将当前歌曲收藏到指定收藏夹。
- **管理**：订阅源增删、全部订阅同步、插件启用/停用/卸载、**插件备份导出/导入**、搜索设置、待机显示、歌词显示调整、主题切换、配置导出/导入、封面形状与转速。

### 搜索设置（音源优先级 + 排序）

- **音源优先级**：列表按「搜索顺序 / 结果靠前程度」排列，点 ↑/↓ 调整；**停用或卸载的插件会保留在列表中**（标注「已停用/已卸载」），恢复后自动回到原位置；已卸载项可点 ✕ 暂时移出。
- **默认排序 / 升降序 / 最多返回结果**：作用于全局聚合结果。

### 插件备份与恢复（每插件 js 直链）

- **导出**：JSON 备份文件，内容为**每个插件各自的 js 直链地址** + 音源优先级顺序；**不含源码、不含订阅合集地址**。
- **导入**：按地址逐个重新拉取安装（清除卸载名单，等同手动导入），恢复音源顺序。
- 卸载会记入 `uninstalled_plugins` 名单，订阅同步时命中名单的插件自动跳过。

### 推荐页后端要点

- 所有接口按 TV 端同名 ViewModel 的解析逻辑 1:1 复刻，方法回退顺序与 `SheetViewModel.fetchPage` 一致。
- 详情回退链每一步都记进响应 `tried:[{method, ok, items|error}]`，排查插件问题直接看它。
- `getTopLists` 返回的是**分组结构** `[{ title, data: [...] }]`，必须拆一层分组并丢弃空 `title` 与空组，否则会把分组名当榜单、把分组对象当榜单传给 `getTopListDetail`。
- 图片一律走 `GET /api/img?url=`（含 `isBlockedHost` SSRF 防护、逐跳校验重定向、`MAX_IMAGE_BYTES` 限制），不能直连图床。
- 插件返回文本需过 `cleanText()`：去掉 U+FFFD 替换符与 C0/C1 控制字符（部分插件歌单名末尾带被截断的多字节字符）。
- 分类接口失败**不算失败**，只回默认分类，与 TV 端 `loadTags` 同策略。
- `callPlugin` 对含 `busy/timeout` 的错误做一次 600ms 等待重试（JS 引擎 lane 忙）。

### 推荐页前端坑（务必避免重犯）

- **`<button>` 里不能放 `<div>` 或 `♪` 占位块**：HTML 解析器遇到 `<button>` 内的 `<div>` 会强制提前闭合 button（button 只能含 phrasing content），卡片结构直接塌掉。**卡片根节点必须是 `<div>`**。
- **`onerror` 属性里绝不能出现双引号**：双引号包裹的属性里再写 `class="ph"` 会提前闭合属性，后面全被当正文（用户看到的 `'">` 溢出）。必须改成 `onerror="artFail(this)"` 调具名函数（`artFail` 用 `createElement` 造占位 `<div class="ph">`）。
- **分页追加不能调用未定义的 `pgBodyAppend`**：`Grid` 声明时已带 `state`，追加元素要 `body.appendChild(el)` 后再 `Grid.observe()`；原写法会直接 `TypeError` 静默失效。
- **CJS 缩进坑**：`pgLoadSheets` 位于 `async function` 体外的一层函数里，**不能加 `await`**，需异步等待时用 `waitSheetLoad()` 轮询。
- 平台 / 分类 chip 作用域只限推荐页（`#pgPlatforms` / `#pgTags`），胶囊样式不要污染其他页面的 `.chip`。

### 推荐页布局规格（定稿，勿再改）

- 平台/分类 chip：胶囊（`border-radius:999px` + `white-space:nowrap`）、`flex-wrap:wrap` 自动换行、横纵间距一致 `8px`。
- 歌单/榜单网格：`.mgrid` / `.strip` = `display:grid; grid-template-columns:repeat(auto-fill,132px)`，**定宽、自动换行、不出现横向滚动条**。
- 卡片：`.mcard` 宽 `132px`，封面 `132x132` + `object-fit:cover`（任何分辨率都裁成正方形）；标题 `-webkit-line-clamp:2` 锁 2 行；副标题 `nowrap` + 省略号。
- **行高必须写死**（`.mcard .s { line-height:16px; height:16px }`）：CJK 字形上下伸展大于拉丁字母，`line-height:normal` 时 CJK 行盒比英文高 4px，同一网格里卡片实测 184/188px 高低不齐。
- 歌曲行封面 `.songrow img { 42x42 }` + `.songrow { min-height:58px }`。
- 页面全局隐藏滚动条（`::-webkit-scrollbar{display:none}` + `scrollbar-width:none`）。
- CDP 实测：390px 下 2 列、768px 下 4 列，30 张卡片宽度全 132px，`horizontallyScrollable:false`。

### HTTP API

```text
GET  /                    → 控制台首页（HTML）
GET  /api/status          → { app, version, host, port, status }
GET  /api/plugins         → 插件列表（启用状态 / loadError）
POST /api/plugins/toggle、/api/plugins/uninstall、/api/plugins/uninstallAll
GET  /api/plugins/export、POST /api/plugins/import   → 插件备份
POST /api/subscriptions、/api/subscriptions/remove、/api/sync
GET  /api/search?q=&sources=&minD=&maxD=&art=1&sort=&asc=
     → 渐进式搜索：立即返回会话 id，后台按音源并行搜索
GET  /api/search/poll?id= → 轮询增量结果 { done/totalEnabled, total, results, perSource }
GET  /api/search/config   → 搜索共享配置（音源优先级/默认排序/最大结果数）
POST /api/search/config   → 保存（管理页「搜索设置」卡片）
GET  /api/plugin/caps     → 所有已启用插件的 topLists / recommend 能力
POST /api/plugin/toplists → getTopLists → { groups:[{title, boards:[…]}] }
POST /api/plugin/tags     → getRecommendSheetTags → { tags:[{id,title}] }
POST /api/plugin/sheets   → getRecommendSheetsByTag(tag, page) → { sheets, isEnd, hasMore }
POST /api/plugin/detail   → 歌单/榜单详情 → { header, music, isEnd, hasMore, tried }
GET/POST /api/idle        → 待机显示（enabled + minutes）
GET/POST /api/meta        → 封面形状 / 黑胶转速 / 旋转方向（coverSpinDir: cw|ccw）等播放显示配置
POST /api/play、/api/play/queue、/api/player/{playpause|next|prev|seek|volume|skip|mode}
GET  /api/player、/api/lyric、/api/themes
     /api/player 队列条目带 faved 标记（"title\0artist" 命中任一收藏专辑即 true，缓存跟随 lists Flow 引用失效），SSE 帧同源
POST /api/theme、/api/lyric、/api/export、/api/import
GET  /api/fav/lists、/api/fav/items、/api/player/fav-albums
POST /api/fav/{lists/create|lists/rename|lists/remove|toggle|play}
POST /api/player/favAt   → 按播放队列索引收藏当前曲（{index, listId}，raw 不随轮询下发）
GET/POST /api/history、/api/history/clear → 播放历史（最多 100 条，供收藏页「🕘 历史」虚拟专辑）
POST /api/plugin/collect → 整张歌单/榜单收藏（{platform, kind, item, listId}，服务端翻页取全量**裸 raw 条目**批量入库，主键去重；上限 50 页/2000 首）
GET  /api/img?url=...     → 图片代理（绕过图床防盗链，控制台封面可见）
```

搜索说明：`sources`（音源多选，逗号分隔）、`minD`/`maxD`（时长秒）、`art=1`（必须有封面）、`sort/asc`（全局排序）。搜索结果实时刷新，全部音源完成后封顶展示前 `maxTotal` 条。

### 测试坑（PowerShell / 模拟器）

- **PowerShell 测不了含中文的请求体**：`Invoke-WebRequest -Body` + `ConvertTo-Json` 会把「QQ音乐」这类平台名按 GBK 发出，服务端收到 `QQ??` 报 `plugin not loaded`。测中文接口**一律用 node 脚本**（`fetch` + `JSON.stringify` 天然 UTF-8）。PS 读 JSON 结果同样会显示乱码，看到乱码先怀疑编码而不是接口。
- **x86_64 模拟器截图不可靠**：`screencap` 会整层丢内容（顶栏/页面「消失」、旧页面鬼影），实际 UI 正常。判定真实状态必须用 `uiautomator dump` + `adb pull` + grep，且 PS 内联 `-match` 中文会因编码失效，必须落地文件搜。
- **touch mode 陷阱**：`adb input tap` 会让设备进入 touch mode，此后 Compose `FocusRequester.requestFocus()` **静默失效**。任意实体按键（keyevent）即退出 touch mode。测试脚本尽量用 keyevent。
- PS 控制台会截断显示长 URL，**别把截断串拷进测试脚本**。

---

## 5. 检查更新与发版

- **应用内检查更新**（「关于」页）：对比 GitHub 最新 Release 与本地版本，有新版则弹窗提示并可直接下载安装（FileProvider 调起系统安装器）。
- **下载线路择优**：版本检测与 APK 下载均优先走国内 CDN 代理，下载前对全部线路做**并行最快探测**（Range 小请求竞速），选响应最快者下载、其余按序兜底，最后回退 GitHub 直连。
- **自动发版**：推送 `main` 即触发 CI——自动递增版本号、打 tag、生产签名构建、创建 GitHub Release 附 APK 与 changelog，本地零操作。
- 版本号**不要手动改**，由 workflow 自动递增（最新 `v*` tag 的 patch+1）。
- 生产签名用 4 个 GitHub Secrets（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS=release` / `KEY_PASSWORD`）；无 Secrets 时构建自动退化为 debug 签名以保证可安装。

---

## 致谢

- [MusicFreePlugins](https://github.com/maotoumao/MusicFreePlugins)（猫头猫）
- [quickjs-android](https://github.com/taoweiji/quickjs-android)（陶维佳）
- [Media3 / ExoPlayer](https://developer.android.com/media/media3)
- [Coil](https://coil-kt.github.io/coil/) / [zxing](https://github.com/zxing/zxing)
- [lrc.cx](https://api.lrc.cx)（歌词与封面补全 API）
