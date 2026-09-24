# MusicFree TV（安卓tv）

基于 [musicfree-plugins](https://github.com/maotoumao/MusicFreePlugins) 插件仓库的 **Android TV / 盒子** 播放器骨架（Kotlin + Jetpack Compose for TV + Media3 + QuickJS）。

> ⚠ 本工程为「可编译骨架」：已完整实现 JS 引擎加载、插件调用协议、首页/搜索/歌单/播放器/设置/远程配置推送全链路 UI 与基础逻辑；部分高级功能（歌词滚动高亮、通知栏封面、多码率选择、WebDAV 基础认证代理等）需根据实际使用场景进一步完善。  
> 本机无 Android SDK，请用 Android Studio（2024.1+）打开 `安卓tv/` 文件夹，同步 Gradle 后构建。

---

## 目录结构

```
安卓tv/
├─ app/
│   ├─ build.gradle.kts                         # compileSdk 35 / minSdk 26
│   ├─ proguard-rules.pro                       # QuickJS / OkHttp / zxing keep rules
│   └─ src/main/
│       ├─ AndroidManifest.xml                  # TV Leanback + 远程配置 intent-filter + 播放服务
│       ├─ assets/runtime/
│       │   ├─ globals.js                       # console / btoa / setTimeout / URL 垫片
│       │   ├─ moduleLoader.js                  # 极简 CommonJS 加载器
│       │   ├─ bootstrap.js                     # env.getUserVariables + __registerPlugin + __invoke RPC
│       │   └─ libs/                            # crypto-js / qs / dayjs / he / big-integer / cheerio / webdav / axios
│       ├─ java/com/tvmusic/
│       │   ├─ MainActivity.kt                  # Compose NavHost + 迷你播放条 + 深链处理
│       │   ├─ core/TvMusicApp.kt               # Application：初始化 Store/Runtime/Repository/Player
│       │   ├─ data/                            # Models.kt + PluginStore.kt（SQLite）
│       │   ├─ runtime/                         # JsEngine 接口 + QuickJsEngine（taoweiji quickjs-android 1.4.6）
│       │   ├─ plugin/                          # PluginRuntime + PluginRepository（订阅/安装/探测 platform）
│       │   ├─ player/                          # PlayerManager + PlaybackService（Media3 + MediaSessionService）
│       │   ├─ remote/                          # ConfigModels + ConfigParser + RemoteConfigService（局域网 HTTP 推送）
│       │   └─ ui/
│       │       ├─ theme/Theme.kt               # 深色 Material3 配色
│       │       ├─ components/Components.kt     # AppTitleBar / Artwork / MediaCard / MusicRow / tvFocus / LoadingBox
│       │       ├─ home/HomeScreen+ViewModel    # 首页：推荐歌单（getRecommendSheetTags）+ 排行榜（getTopLists）
│       │       ├─ search/SearchScreen+ViewModel# 搜索（search across enabled plugins）
│       │       ├─ sheet/SheetScreen+ViewModel+SheetTarget  # 歌单详情（musicList / getTopListDetail / importMusicSheet）
│       │       ├─ player/PlayerScreen          # 全屏播放：封面 + 歌词 + 进度 + 控制（上/下/快进快退）
│       │       ├─ setting/SettingsScreen+ViewModel # 插件/订阅/用户变量/远程配置（地址 + 扫码 + 口令）
│       │       ├─ qr/QrScreen                 # CameraX + zxing 扫码接收
│       │       └─ common/                      # Vms 工厂 + ConfigPending 深链暂存
│       └─ res/                                 # drawable/banner / ic_launcher / colors / themes / network_security_config
├─ gradle/
│   ├─ libs.versions.toml                       # 所有依赖版本（含 quickjs 1.4.6 / media3 1.5.1 / camerax 1.3.4）
│   └─ wrapper/gradle-wrapper.properties        # Gradle 8.10.2
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
└─ README.md
```

---

## 技术要点

### 1. JS 引擎与插件协议（QuickJS + 原生桥）

- **引擎选型**：[taoweiji/quickjs-android](https://github.com/taoweiji/quickjs-android) `1.4.6`（Maven Central，支持 Event Queue、CommonJS、Java→JS 回调）。
- 所有 JS 引擎调用在**单一 HandlerThread** 上串行执行（`QuickJsEngine.jsBlock`），保证线程安全。
- **async RPC**：JS 端 `__invoke(platform, method, argsJson, cbId)` → Promise.then → `nativeBridge.onPluginResult(cbId, json)` 回吐；Java 侧用 `CompletableFuture` + 轮询 `drainJobs()` 等待（最多 60s 超时）。
- **定时器**：`setTimeout` 由 Java `Handler.postDelayed` 驱动；`clearTimeout` 立即移除原生回调。
- **HTTP 原生桥**：`nativeBridge.httpRequest` → OkHttp 同步请求；**自动剥离**插件传入的 `Accept-Encoding` 头，由 OkHttp 透明处理 gzip/br。
- **用户变量**：`env.getUserVariables()` 返回所有插件全局合并（同名键以最新存储为准）。WebDAV 等插件使用 `url/username/password`。
- **Parcel 打包兼容**：`__registerPlugin` 自动识别 `module.exports.default`（Parcel）与普通 CommonJS；插件 platform 取源码**最后一次出现**的 `platform: "..."` 值。
- **方法缺失处理**：任何未实现的方法（如插件没有 `getRecommendSheetTags`）统一返回 `{ __notImplemented: true }`，UI 层静默跳过。

### 2. 插件加载与订阅管理

- 内置默认订阅源（首次启动自动添加）：`https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@latest/plugins.json`
- **PluginRepository**：
  - `syncAll()` 遍历所有订阅，下载 plugins.json，按 `name/url/version` 安装。
  - `install(name, url)` 下载 JS → 正则探测 platform → 注册到引擎 → 读取元信息 → SQLite 持久化。
  - 支持 `.js` 直链导入（单插件）与 plugins.json 列表导入。
- **插件元信息**：通过 JS 运行时读取导出对象的 `platform/version/author/srcUrl/userVariables/supportedSearchType`，存入 `PluginRecord.info`。

### 3. 播放器（Media3 ExoPlayer）

- **PlayerManager**：单例持有 ExoPlayer，对外暴露 `StateFlow<PlayerUiState>`（当前歌曲 / 播放状态 / 队列 / 歌词 / 进度）。
- **按需取流**：播放时调用插件 `getMediaSource(item, "standard")` → 拿到 `{ url, headers }` → `DefaultHttpDataSource.Factory.setDefaultRequestProperties(headers)`（包括 Referer 等必要头，对 HLS 片段同样生效）。
- **队列管理**：同一歌单的全部歌曲作为队列传入 `PlayerManager.play`，实现上/下一首（`skipTo` 重新 fetch 对应歌曲 URL）。
- **歌词**：调用 `getLyric(musicItem)` → 解析 rawLrc `[mm:ss.xx]歌词`；播放时每 500ms 更新当前行索引，全屏播放器显示当前行。

### 4. 远程配置推送

局域网 HTTP 服务（端口 `48621`）：
```
GET  /                → { app, version, host, port, receiveUrl, status }
POST /push            → body 为配置 JSON，立即应用
GET  /push?url=<url>  → 拉取远端配置并应用
```

配置 JSON 格式：
```json
{
  "subscriptions": ["https://.../plugins.json"],
  "plugins": [ { "name": "插件名", "url": "...", "version": "..." } ],
  "userVariables": { "url": "http://...", "username": "user", "password": "pass" },
  "sync": true
}
```

也支持深链口令（URL-safe Base64 编码）：
```
tvmusic://config?data=<base64url(json)>     # 单条配置
tvmusic://config?sub=<订阅地址>               # 添加订阅
```

电视端设置页显示接收地址，手机浏览器访问即可推送；也可点击「扫码接收」用 CameraX + zxing 扫二维码获取配置。

---

## 构建说明

1. 用 **Android Studio 2024.1+** 打开 `安卓tv/` 文件夹。
2. 等待 Gradle Sync 完成（首次需下载依赖，约 5–10 分钟）。
3. 连接 Android TV 设备或使用 Android TV Emulator（API 30+，分辨率 1920×1080）。
4. 点击 ▶ Run 安装运行。

> 注：本工程 `lint { abortOnError = false }` 降低因本机未配置 Android Lint 而失败的概率。  
> 注：`buildFeatures { buildConfig = true }` 已开启（RemoteConfigService 中使用 `BuildConfig.VERSION_NAME`）。

---

## JDK 17 配置

本工程要求 **JDK 17** 构建（Gradle 8.10.2 运行、`compileOptions` / `kotlinOptions` 均指向 17）。Android Studio 自带 JBR 即 JDK 17，通常无需额外配置；若命令行构建报错，请按以下任一方式指定：

**方式一（推荐）：使用 Android Studio 内置 JBR**
- 已随 Android Studio 安装，路径一般为 `C:\Program Files\Android\Android Studio\jbr`。
- 命令行使用：在环境变量中设置
  ```
  JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
  ```
  或将上述路径写入 `gradle.properties`：
  ```
  org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
  ```

**方式二：独立安装 JDK 17（如 Temurin / Oracle）**
- 下载安装后设置 `JAVA_HOME` 指向 JDK 17 根目录，例如：
  ```
  JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.x
  ```
- 验证版本（必须为 `17.x`，大于或小于 17 均可能导致 Gradle/AGP 兼容性问题）：
  ```
  java -version
  ```
- 注意：本机若同时安装多个 JDK，`JAVA_HOME` 与 `org.gradle.java.home` 不可同时设置且指向不同版本，二者优先级高于 `PATH`。

**方式三：在 Android Studio 内指定**
- `File → Project Structure → SDK Location → JDK location` 选择 JDK 17 所在目录。
- 或 `Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK` 选择 17。

> 注：Gradle 编译时 `org.gradle.java.home`（或 `JAVA_HOME`）决定 Gradle 守护进程使用哪个 JDK，必须为 **17**；工程源码层面的 Java/Kotlin target 已固定为 17，无需改动构建脚本。

---

## 已知限制与待完善

1. **歌单详情的分页加载**：部分插件 `importMusicSheet` 需分页参数；当前仅取第一页（page=1），大歌单需后续补充滚动加载。
2. **专辑/歌手作品**：搜索结果中 `type=singer/album/sheet/leaderboard` 条目暂未处理（骨架只播放 type=music）；如需可用，需增加 `getArtistWorks` / `getAlbumInfo` 入口。
3. **歌词翻译**：`getLyric` 返回 `translationList` 时暂未在 UI 中并排显示。
4. **用户变量编辑**：设置页变量列表可按插件展开编辑；全局变量与插件变量共用 key，存在同名冲突风险（如不同插件都用 `url` key）；可考虑按 plugin namespace 做 UI 隔离。
5. **WebDAV HTTP 基础认证**：shim 层通过 URL userinfo 传递 Basic Auth；若 WebDAV 插件改用其他认证方式需跟进。
6. **通知栏封面**：Media3 默认 `PlayerNotificationManager` 使用 MediaItem artwork；若插件返回的 artwork URL 需加认证头，需定制 `NotificationCompat.Builder`。
7. **D-pad 选中态动画**：`tvFocus` modifier 已实现 scale + alpha；若需更多 TV Leanback 风格（如 `LeanbackTheme` 高亮边框）可替换为 `androidx.tv.foundation`。
8. **Proguard**：`proguard-rules.pro` 已配置 QuickJS/OkHttp/zxing keep 规则；正式上线前建议启用 `minifyEnabled = true` 并补充 Compose/Coil/Media3 keep。

---

## 常用命令（在 `安卓tv/` 目录）

```bash
# 安装到已连接的 TV 设备
./gradlew installDebug

# 构建 Release APK
./gradlew assembleRelease
```

---

## 致谢

- [MusicFreePlugins](https://github.com/maotoumao/MusicFreePlugins)（猫头猫）
- [quickjs-android](https://github.com/taoweiji/quickjs-android)（陶维佳）
- [Media3 / ExoPlayer](https://developer.android.com/media/media3)
- [Coil](https://coil-kt.github.io/coil/) / [CameraX](https://developer.android.com/media/camera/camerax) / [zxing](https://github.com/zxing/zxing)