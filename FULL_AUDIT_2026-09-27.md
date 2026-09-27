# MusicFreeTV 全面审查报告（2026-09-27）

> 覆盖 8 个维度：代码质量 / 性能 / Android TV 体验 / UI 优化 / 工程配置 / 稳定性与健壮性 / 兼容性 / 可观测性。
> 共 **58 项**，按 高(🔴) / 中(🟡) / 低(⚪) 三级分类，每项标注维度与文件位置。
> 本次审查基于上一轮 `REVIEW_2026-09-27.md` 之后的最新代码（v0.3.15）。

---

## 🔴 高优先级（13 项）

### 安全 / 风险（4 项 —— 最高优先）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| H1 | 可观测性 | 全仓 | **无任何崩溃/ANR 监控**。无 CrashHandler / Firebase / Bugly。native 库（js_job_pump.c 通过 dlsym 进 libquickjs）+ JS 桥使崩溃率天然偏高，线上崩溃完全无感知。 |
| H2 | 工程配置/安全 | `AndroidManifest.xml:28` + `network_security_config.xml:4` | **全局放开明文 HTTP**。`usesCleartextTraffic="true"` + `base-config cleartextTrafficPermitted="true"`，第三方插件 JS 可发任意 HTTP 请求，中间人可替换为恶意 JS。 |
| H3 | 工程配置/安全 | `AboutScreen.kt:312-327` | **自更新无完整性校验**。只下载 APK 到 getExternalFilesDir 就 ACTION_VIEW 安装，无版本比对、无 SHA-256、无签名校验、无 TLS 强制。结合 H2 = 远程任意代码执行链。 |
| H4 | 工程配置 | `proguard-rules.pro:15-30` | **ProGuard 过宽**。OkHttp/zxing/整个 media3/Coil 全量 `-keep`，放弃混淆裁剪，包体积大 30-50%，削弱反编译保护。这些库有 consumer-proguard，基本无需 -keep。 |

### 启动 / 性能 / 稳定性（5 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| H5 | 性能/启动 | `TvMusicApp.onCreate` → `PluginRuntime.create` → `QuickJsEngine.initialize` | **冷启动同步创建 3 台 QuickJS 引擎**，主线程读 10+ 个 assets JS 文件 + 建 runtime。TV 弱 CPU 上是启动耗时大头。 |
| H6 | 稳定性 | `QuickJsEngine.jsBlock` | **无超时 `future.get()`**。JS 线程挂死（插件死循环）= 主线程 ANR，无兜底。`invoke` 有 tryLock+deadline，此处却缺。 |
| H7 | 性能/启动 | `TvMusicApp.onCreate` `readPluginSourcesFromDevice` | **启动期主线程多路径文件 IO**，探测 6 个候选路径 readText。外置存储首次访问百毫秒级抖动。 |
| H8 | 稳定性/内存 | 全仓（搜索 0 命中） | **缺 onTrimMemory / registerComponentCallbacks**。TV 内存吃紧时不清 Coil 缓存 / 多余 JS lane / 预加载缓存，后台存活率与二次冷启率变差。 |
| H9 | 性能/启动 | `PluginStore`（loadPluginMetas/loadPlugins）+ `repository.warmup` | **插件 DB 查询与预热在主线程同步执行**，warmup 物化全部插件源码（数 MB 分配），启动链主线程 SQLite + JSON 分配 + JS 注册串行。 |

### TV 体验 / UI（4 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| H10 | TV 体验/焦点 | `PlayerScreen.kt`（全文） | **播放页无初始焦点**，进入后遥控器可能"无响应"，焦点落在不可见根容器上，需先按方向键唤醒。 |
| H11 | UI/可读性 | `Components.kt:93-134`（tvFocus） | **未聚焦项 alpha 0.92 / 0.62**，整屏内容常半透明，alpha 0.62 与背景对比度必然低于 WCAG 建议，10 英尺可读性差。 |
| H12 | UI/一致性 | `SearchScreen.kt:524-548`、`PlayerScreen.kt:408-425/694-802` | **浅主色主题下硬编码 `Color.White`**，浅底白字不可读（主题含近白色 primary 时）。 |
| H13 | TV 体验/焦点 | `Components.kt:174-197`（AppTitleBar） | **初始焦点写死在"首页"页签**，从子页返回/切 Tab 后焦点永远跳回首页，无焦点记忆。 |

---

## 🟡 中优先级（24 项）

### 性能（6 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| M1 | 性能/网络 | `QuickJsEngine:82`、`PlayerManager:143/151`、`PluginRepository:36`、`RemoteConfigService:57`、`AboutScreen:349` | **OkHttpClient 不复用，10+ 个独立客户端**（每台 JS 引擎一个）。连接池+线程池+DNS 各自独立，内存/线程被放大。 |
| M2 | 性能/弱网 | `PlayerManager.fetchFallbackLyric` | **歌词兜底重试风暴**：repeat(2) × 全候选 × callTimeout 20s。可缺失的辅助信息按关键路径重试。 |
| M3 | 性能/播放 | `PlayerManager.requirePlayer` | **未配置 DefaultLoadControl**（起播/回退/最大缓冲未按 TV 调），且每次 play 在主线程重建 DataSource。 |
| M4 | 稳定性 | `PlaybackService.kt` | **缺 onTaskRemoved**，最近任务划掉后无停播/保存快照/释放处理。 |
| M5 | 性能 | `RemoteConfigService:245/302/1099` | **HTTP 线程里 runBlocking**（导入/搜索设置），占死线程池槽位。 |
| M6 | 性能/启动 | `RemoteConfigService.ensureStarted`（TvMusicApp.onCreate） | **冷启动即启动前台服务**（通知渠道+ServerSocket+resolveLocalIp 遍历网卡），拉长启动路径且常驻端口。 |

### 图片 / UI / TV 体验（10 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| M7 | 性能/图片 | `Components.kt:150-161`、`SearchScreen.kt:514-517` | **AsyncImage 无 placeholder/error**，弱网坏链空白无反馈；未统一 ImageLoader 策略（内存缓存上限+磁盘缓存）。 |
| M8 | 性能/网络 | `QuickJsEngine.httpRequest` | **JS 桥 httpRequest 在 JS 线程同步阻塞**，慢请求拖住整台引擎后续所有调用（含搜索/播放）。 |
| M9 | TV 体验/焦点 | `Components.kt:66-85`（tvInitialFocus） | **focus 重试轮询脆弱**：repeat(20)+delay(80) 最长 1.6s，期间用户按键被抢焦。应改 onGloballyPositioned。 |
| M10 | UI/适配 | `SearchScreen.kt:85-155` | **搜索页固定 weight + 50.dp 输入框**，720p 下键盘区过窄、输入框偏矮，无分辨率自适应。 |
| M11 | UI/可读性 | 多处（Components:306、FilterChip:256、PlayerScreen:265/408、HomeScreen:324、歌词译文） | **大量 10-11sp 小字号**，不符 10 英尺规范（正文/辅助应 ≥12-14sp）。 |
| M12 | TV 体验/焦点 | MainActivity MiniPlayerBar | **迷你播放条不可聚焦**：深色硬编码 + pointerInput（非焦点项），D-pad 无法聚焦进播放器。 |
| M13 | 工程配置 | ThemeManager ThemeTokens | **focusGlow token 声明后从未使用**（tvFocus 未实现 glow 渲染），死配置。 |
| M14 | UI/内容 | `SearchScreen.kt:666` | **热门搜索硬编码歌手列表**，无法配置、无本地化、无空态管理。 |
| M15 | TV 体验/导航 | Home/TopList 横向 LazyRow | **长列表无快速导航**（跳行首/行尾、长按加速），长榜单 D-pad 逐张翻效率低。 |
| M16 | TV 体验/导航 | MainActivity | **首页 popUpTo inclusive 重建**，切回即丢滚动位置，与其他页签行为不一致。 |

### 工程配置 / 可观测性（5 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| M17 | 工程配置/包体积 | `build.gradle.kts:38-41` | **ABI 三件套同包未启用 splits**（arm64+armeabi-v7a+x86_64），quickjs 自带 4 ABI so，为 4 年前 QuickJS 背 3 套 ABI 死重量。 |
| M18 | 工程配置 | `libs.versions.toml` | **依赖整体偏旧**（AGP 8.9.2/Kotlin 2.0.21/Compose BOM 2024.12/media3 1.5.1/okhttp 4.12/coil 2.7），且 lint 屏蔽 OldTargetApi。coil 1.x 已停止安全更新。 |
| M19 | 可观测性 | 多处 | **日志规范混乱**：TAG 五种风格并存、release 保留 Log.w/e、插件 JS console.log 直接污染 logcat、http error 打完整 URL（可能带 token）。无 Timber/L 封装。 |
| M20 | 可观测性 | 全仓 | **性能监控/埋点/报警空白**。无冷启动时长、首开时间、缓冲率、插件调用成功率/超时率采集。插件机制下"某音源在某盒子超时"无感知。 |

### 稳定性（3 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| M21 | 工程配置/安全 | `build.gradle.kts:11-27,57-69` | **keystore.properties 解析脆弱 + 静默回退未签名包**。手 split '=' 解析；密钥缺失时 release 不签名也不报错，可能误发布。 |
| M22 | 稳定性/native | `js_job_pump.c` | **dlsym 私有符号补丁风险**：绕过官方 JNI，升级 quickjs 即崩；dlsym(RTLD_DEFAULT) 会从全局符号表拿错对象；无线程安全校验。 |
| M23 | UI/状态 | 多页 | **加载/空/错误三态不齐**：部分错误态无可聚焦"重试"；AboutScreen 纯文本区 D-pad 落焦路径待验证；LoadMoreFooter 悬浮遮挡风险。 |

### TV 体验补充（1 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| M24 | TV 体验/焦点 | `SearchScreen.kt:687-708` | **KtvChip 内 "×" 嵌套 clickable**，D-pad 可达性残留问题（SheetScreen 已改兄弟节点，此处同类未改）。 |

---

## ⚪ 低优先级（21 项）

### 代码质量 / 工程配置（12 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| L1 | 工程配置 | `build.gradle.kts:115-120` | Compose BOM 已引入仍逐条枚举 ui/foundation/runtime，冗余（material3 已传递）。 |
| L2 | 工程配置 | `build.gradle.kts:128` | media3-ui 引入但 Compose UI 未用 PlayerView/PlayerControlView，可裁剪（~600KB）。 |
| L3 | 工程配置 | `libs.versions.toml:22` | lifecycle-process 仅一处使用，可用 ActivityLifecycleCallbacks 计数替代。 |
| L4 | 工程配置 | `build.gradle.kts:99-103` | UnusedResources 在 lint 中整体禁用，死资源永远不会被报。 |
| L5 | 工程配置 | `build.gradle.kts:86-88` | packaging.resources.excludes 只排除 2 项，可加 DEPENDENCIES/LICENSE*/NOTICE*/versions。 |
| L6 | 兼容性 | res 仅默认 values/ | 文案硬编码进 Kotlin，无 i18n；即便不国际化也建议统一进 strings.xml。 |
| L7 | 代码质量 | `PlaybackStore.kt:433` 等 | 日志字符串插值 release 也执行，建议 Timber 或 BuildConfig.DEBUG 守卫。 |
| L8 | 工程配置/安全 | `PluginRepository.kt:532` | 用 SHA-1 做插件源码指纹（非安全用途），建议改 SHA-256 前 12 位。 |
| L9 | 性能 | `PlaybackService.kt:117-122/49-52` | BitmapFuture.get() 无超时 wait()，decodeBitmap 无 inSampleSize，与 PlayerManager.decodeArtwork 不一致。 |
| L10 | 稳定性 | `PlaybackStore.kt:62-73` | 收藏落盘防抖依赖主线程 Looper，进程被杀丢最近一次变更。 |
| L11 | 性能 | config 三件套（Idle/Meta/Search）| onCreate 同步读 SharedPreferences，叠加启动耗时。 |
| L12 | 性能 | `PlayerManager` | 错误 1200ms 固定延迟不可配置；1s 主线程 ticker 持续唤醒不利待机功耗。 |

### UI / TV 体验（9 项）

| # | 维度 | 位置 | 问题 |
|---|------|------|------|
| L13 | UI/一致性 | `Components.kt:58/136-171`、`PlayerScreen.kt:654` | 硬编码颜色残留：ModalScrim 0xAA000000、Artwork 占位渐变、0x22FFFFFF 等。 |
| L14 | TV 体验/无障碍 | 全项目 | contentDescription 覆盖率仅 ~12 处，图标型按钮（♥/♡/☑/☐/⟳）多数无描述。 |
| L15 | UI/适配 | 多处 | 固定 dp 尺寸密集（NowPlayingPanel 260.dp、封面 300.dp、MediaCard 168.dp、ModalCard 420.dp），4K/720p 比例失衡，无 overscan 安全区。 |
| L16 | UI/可读性 | HomeScreen:281 等 | onSurfaceVariant.copy(alpha=0.7f) 小字降透明度，灰上深灰对比度偏低。 |
| L17 | 性能 | `QuickJsEngine:82-85` | 自带 OkHttp 无 callTimeout、无连接池定制。 |
| L18 | 兼容性 | AndroidManifest | 需确认 screenOrientation/resizeableActivity 与 overscan 适配（审查未完整覆盖 manifest 布局细节）。 |
| L19 | UI/内容 | 各页 EmptyState | 多数无 actionLabel（重试操作）。 |
| L20 | 代码质量 | 多处 | 重复/超长函数残留（此前评审已部分处理）。 |
| L21 | 工程配置 | `settings.gradle.kts` / 根 build.gradle | 构建优化可进一步加强（未深入，影响小）。 |

---

## 整改路线图建议

### P0 — 立即做（安全风险，本周）
1. **H1** 崩溃/ANR 监控（最低成本：自建 setDefaultUncaughtExceptionHandler + 文件落盘 + 下次启动上报；NDK 符号化）
2. **H3** 自更新校验（HTTPS 版本清单 + 下载后 SHA-256 + 签名校验；做不到先去掉 REQUEST_INSTALL_PACKAGES 改跳浏览器）
3. **H2** 收紧明文 HTTP（domain-config 白名单，仅局域网放行；更新域强制 HTTPS；移除 manifest usesCleartextTraffic）
4. **H4** 收紧 ProGuard（删掉 media3/okhttp/coil 全量 -keep，立减包体积 30%+）

### P1 — 高优先（启动/ANR/焦点，下个迭代）
5. **H5/H9** 启动路径：JS 引擎惰性/后台预热、插件 DB/预热下沉 IO、启动打 Trace
6. **H6** jsBlock 加超时（统一 future.get(timeoutMs) + 引擎重启/摘除）
7. **H7** 插件目录扫描移 IO 线程
8. **H8** onTrimMemory 注册（清 Coil/多余 JS lane/预加载）
9. **H10/H13** 焦点：播放页加初始焦点、焦点记忆（跟随 selectedTab）
10. **H11** tvFocus 去掉非焦点透明度衰减（仅焦点边框/缩放）

### P2 — 中优先（网络/播放/日志/埋点）
11. **M1** OkHttpClient 统一共享（2-3 个单例按超时档位）
12. **M3** DefaultLoadControl 按 TV 调 + DataSource 复用
13. **M2/M8** 歌词兜底降重试、JS httpRequest 异步化
14. **M19** 日志统一 Timber/L 封装 + release 不写 logcat + URL 脱敏
15. **M20** 最小 Metrics 采集（冷启动/首开/插件调用成功率，经 RemoteConfigService 暴露 /metrics）
16. **M4** PlaybackService onTaskRemoved

### P3 — 低优先（整洁/适配/依赖，排期）
17. **M17** ABI splits（AAB 或 arm64 单包，评估 v7a 占比）
18. **M18** 依赖升级节奏（半年一次：media3/okhttp/coil3）
19. **M22** quickjs 封装评估替换（或短期加 sanity check + 线程校验）
20. UI 小字号/固定 dp/overscan/三态/无障碍 contentDescription 补齐
21. L1-L21 工程整洁项

---

## 证据缺口（如实标注）

- `PluginRepository.kt` 约 500/629 行后未逐行读完（安装/下载路径可能还有额外阻塞点）。
- `RemoteConfigService.kt` 181-502 行各 API 处理逻辑未逐行确认。
- AndroidManifest 的布局级 overscan/横屏适配细节（L18）未完整验证。
- L20/L21 为归并占位，需逐项细化。

## 与上一轮的关系

上一轮 `REVIEW_2026-09-27.md` 的 26 项已全部修复上线（v0.3.13-15）。本报告是**全新一轮**、覆盖更广维度（新增安全/可观测性/工程配置/兼容性/启动性能）的审查，**无重复项**。上一轮已修项（如 PlaybackStore 线程安全、info!! 清理、协程泄漏）不再列出。
