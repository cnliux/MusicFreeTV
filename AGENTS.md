# AGENTS.md — MusicFreeTV

项目级持久记忆文档。每次会话自动加载。修改任何流程前先读本文件。

## 项目概览

- 仓库：`https://github.com/cnliux/MusicFreeTV.git`（分支 `main`），Android TV 音乐播放器，Kotlin/Gradle（AGP）。
- APK 包名：`com.tvmusic`。
- 核心源文件：
  - `app/src/main/java/com/tvmusic/player/PlayerManager.kt`（播放核心）
  - `app/src/main/java/com/tvmusic/config/MetaSettings.kt`（lrc.cx 兜底配置，本会话新增）
  - `app/build.gradle.kts`（版本号定义）

## 版本管理（重要）

- `app/build.gradle.kts:33-34`：`versionCode` / `versionName`。**不要手动改**，发版由 Actions 自动递增。
- 规则：最新 `v*` tag 的 patch+1（如 v0.3.2 → v0.3.3），`versionCode` +1。由 workflow `release-on-main` job 自动完成并回推 `release: version x.y.z` 提交 + tag。

## 发版流程（全自动，本地零操作）

推送 `main` = 完整发版。`.github/workflows/android.yml`：
- `push main` → `release-on-main` job 单次跑完：算版本 → 改 versionCode/versionName → 提交 → 打 tag → 回推 main+tag → 签出构建（生产签名）→ `gh release create` 建 Release 附 APK + 自动 changelog。
- `push v* tag` / PR / `workflow_dispatch` → `build` job：构建；tag 时幂等发布（Release 已存在则跳过）。
- 关键坑：内置 `GITHUB_TOKEN` 回推的 commit/tag **不会再次触发 workflow**（GitHub 防递归），所以 bump+构建+发布必须在同一 job 内完成。不要拆成「bump 回推再等 tag 触发」。
- `gh release create` 用的是 runner 预装 gh + `GH_TOKEN`，无第三方 action。
- Release 命名惯例：`MusicFreeTV vX.Y.Z`。已有 Release：v0.3.3（最新）、v0.3.1 等。

## 签名与 Secrets

- 4 个 GitHub Secrets（已存在并验证）：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS=release`、`KEY_PASSWORD`。
- 本地 `keystore.properties` / `keystore/release.jks` 被 `.gitignore` 排除，切勿提交。
- CI 生产签名已端到端验证：产线 apksigner SHA-256 `2d5e7bc5e75f3bd1fef199ea9be30b1254eadedf18e2ac291b7c65fb3200a5cd` == 本地 release.jks 指纹。
- 无 Secrets 时构建自动退化为 debug 签名（保证可安装）。
- Secrets 更新方法：REST API + `pynacl`（libsodium sealed box）密封加密（`~/.github/workflows`… 见下）；`gh auth` 需要 `read:org` 作用域，当前 PAT 只有 repo 不可用。

## 本地工具（仅开发调试用，与发版无关）

- adb：`G:\GAndroidSDK\platform-tools\adb.exe`
- 构建 debug：`.\gradlew.bat :app:assembleDebug`；release：`.\gradlew.bat :app:assembleRelease`
- 重启应用（模拟器/盒子）：`adb shell am force-stop com.tvmusic` 后 `adb shell monkey -p com.tvmusic -c android.intent.category.LAUNCHER 1`
- 看日志：`adb logcat -s ...` / 抓包用 `adb reverse tcp:22222 tcp:22222`

## 网络环境坑（win32 本机）

- `github.com` 直连间歇失败（常见：仅解析 20.205.243.166 且连不上）。失败时用一次性代理参数重试：
  `git -c http.proxy=http://127.0.0.1:10808 -c https.proxy=http://127.0.0.1:10808 push ...`
- API  直连不通时同样走 `curl -x http://127.0.0.1:10808`。
- API 匿名限流 60 次/小时，频繁轮询会 403 限流（带 Authorization: token 可提高配额，勿滥用、用完删临时文件）。

## Git 认证

- git 凭据管理器存有 cnliux 的 40 位 PAT（scope=repo）。
- 提取命令（向 stdin 喂三行，不要用其它格式）：`"protocol=https`nhost=github.com`n" | git credential fill`，输出 `password=` 行即 token。
- 临时令牌文件用后必须删除（历史用 `C:\Users\Administrator\AppData\Local\Temp\opencode\gh_token.txt`）。

## PowerShell 注意事项

- OS：win32，Shell：PowerShell 5.1。不支持 `&&`，用 `cmd1; if ($?) { cmd2 }`。
- 直接在命令行内联中文会 GBK 乱码；含中文的命令先 `Write-Host` 或用写工具建脚本文件再执行。
- 网络输出可能显示为系统字体编码乱码，属正常，不影响执行。

## lrc.cx 兜底实现（已上线验证）

- 源：`app/src/main/java/com/tvmusic/player/PlayerManager.kt`
  - `withFallbackArtwork`（:1013）：无封面时从 lrc.cx `/cover` 取图（部分歌 301→Apple CDN，OkHttp 自动跟随）。
  - 封面根因修复：`play()`（:599-604）必须把回退封面写进**整个队列** `effectiveQueue = provided.map { withFallbackArtwork(it) }`，且 `current = effectiveQueue[startIndex.coerceIn(...)]`。因为 `onMediaItemTransition`（:500）会用 `queue[idx]` 覆盖 `current`，只写 `current` 会被打回 ♪。
  - `fetchFallbackLyric`（:1026）：来自 lrc.cx `/lyrics`（返回 LRC 文本；偶发 JSON 批量形态）。**坑**：Android 宽松 `JSONArray(body)` 会把 `[Verse]` 开头的 LRC 解析成 `["Verse"]`（首元素非对象 → 提前 return 丢弃真实歌词）。已加固：仅当首元素是对象且带非空 `lyrics` 才按 JSON 处理，否则 `parseLrc(body)`（1307 字节=677 字符完整 LRC 已验证，设备端解析 24 行）。
  - 调试日志（MaterialYou 调试入口打印 `parsed lines=N`）已在最终干净构建中移除。
- lrc.cx 透明 gzip，OkHttp 自动解压，无需额外处理。

## 测试/验收惯例

- 改完播放/歌词/封面逻辑后：本地 `assembleDebug` 装模拟器，用搜索结果（队列播放路径）验证以覆盖 `onMediaItemTransition` 管线；日志确认 `parsed lines=N` 后再出干净构建。
- CI 验证：推 main 自动构建 + 发布；检查对应 Release assets=1。
- **模拟器截图假象（大坑）**：x86_64 模拟器上 `screencap` 会整层丢内容（顶栏/页面"消失"、旧页面"鬼影"），实际 UI 完全正常。判定真实状态必须用 `uiautomator dump` + `adb pull` + grep（PS 内联 `-match` 中文会因编码失效，必须落地文件搜）。keyevent"被吞"多为此假象叠加弹框拦截点击。
- 页签/入口 navigate 已全部加 `launchSingleTop`（MainActivity）：重复点击不叠层。导航曾出现真实的双层 recommend 叠层（弹框拦截第一次点击、第二次点击重复入栈）。navigation-compose 已升 2.8.9。

## UI 组件约定（2026-09 重构后）

- `Components.kt` 共享件：`ModalCard(title, subtitle?, width=420.dp, onDismiss?, bottomBar?, content)`、`DialogTextButton`、`LyricLineBlock(line,isCurrent,lrcColor,fontSizeSp:Float)`、`BackTopBar(title,onBack,titleSize=22.sp,trailing?)`、`EmptyState`、`LoadMoreFooter`、`ModalScrim`。
- 弹层统一 `AnimatedVisibility(fadeIn+scaleIn(0.96f) / fadeOut+scaleOut(0.96f))` 包裹；对话框一律 ModalCard，不再手写遮罩。
- NavHost 自定义过渡：push=slideIn(it/4)+fadeIn/fadeOut；pop=fadeIn/slideOut(it/4)+fadeOut。
- `SheetTarget`：`set()` 写入、`consume()` 原子取出即清空（getAndUpdate）；不再裸 var。
- QrImage 用 `produceState`+`Dispatchers.Default` 生成二维码（勿回退到 remember 主线程生成）。
- strings.xml 只保留 `app_name`（其余 30+ 条无人引用已删）；UI 文案直接内联中文。