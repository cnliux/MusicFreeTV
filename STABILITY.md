# 稳定性防回归清单（卡死 / 闪退）

本项目历史上出现过的卡死与闪退问题及其根因，全部收敛为下列规则。
**改代码时对照检查，Code Review 时按此清单过一遍。**

---

## 一、闪退类

### R1. LazyColumn / LazyRow 的 key 禁止使用裸业务字段
- **事故**：搜索页内层 LazyRow 用 `plugin-id-name` 做 key，插件返回重复 id+name 的卡片（同名专辑、缺失 id、翻页重叠）时，Compose 直接抛 `IllegalArgumentException: Key was already used` 闪退。
- **规则**：
  - `items(key=...)` 的 key 必须保证集合内全局唯一。无法保证唯一时改用 `itemsIndexed`，key 拼接索引兜底：`"c_${i}_${业务字段}"`。
  - 聚合外部数据（插件/网络）的列表，一律视为**可能含重复**。
  - 大列表用构建期 `uniqueKey()`（HashSet 去重 + 序号兜底），参考 `SearchScreen.kt#ResultList`。
  - key 中含有可空业务字段时，必须补一个必唯一的字段兜底（如插件 name），参考 `HomeScreen.kt` 音源 chips。

### R2. 跨线程 Future 的异常必须解包 cause
- **事故**：`future.get()` 抛 `ExecutionException` 包裹真实的 `PluginCallException`，上层按类型判断失效，把堆栈原文显示给用户。
- **规则**：`ExecutionException` / `CompletionException` 必须取 `cause` 抛出，再做类型判断。

### R3. 异步竞态状态必须加锁或原子化
- **事故**：引擎池 `platformSources` 被多线程同时读写导致状态错乱。
- **规则**：多线程共享的可变 Map/Set 用 `synchronized`、`ConcurrentHashMap` 或单线程 confinement；评审时问一句"这个字段谁写、谁读、在哪个线程"。

### R4. 异步结果必须带会话代数校验
- **事故**：快速切歌时旧歌词/旧解析结果后返回，覆盖新歌状态。
- **规则**：所有"发起异步 → 结果回写 UI"的路径带代数计数器（`playSession`）或 key 比对，过期结果直接丢弃；可取消的协程在切换时主动 cancel（`lyricJob`）。

### R5. JSON 解析逐字段判空
- **规则**：插件返回的数据结构不可信：`optJSONObject`/`optJSONArray` 可空链式取值，禁止 `getJSONObject` + `!!`。条目必需字段缺失时跳过该条（`parseEntries` 模式），不让单条脏数据炸整个页面。

### R6. 索引回退不能越界语义化
- **规则**：`indexOfFirst` 返回 -1 时 `coerceAtLeast(0)` 只防崩溃不防播错歌；搜索结果点击播放前需确认目标仍在当前队列（`SearchViewModel.play` 已有防护，改代码时保持）。

---

## 二、卡死类（TV 端）

### R7. 禁止把大字段物化进常驻内存
- **事故**：插件仓库 StateFlow 持有全部插件 JS 源码（每个几百 KB~MB）；远程管理插件页每次操作都全量物化 → TV 端 GC 风暴 → 整机卡死。
- **规则**：
  - 列表展示 / StateFlow / 远程 API 只查元数据（`PluginStore.loadPluginMetas()`，不 SELECT source 大字段）。
  - 需要源码的场景（引擎注册）才走全量查询。
  - 高频路径的 SQLite 查询必须明确列名，禁止 `SELECT *` 后丢弃大列。

### R8. 数据库连接必须常驻
- **规则**：一律走 `SQLiteOpenHelper` 常驻连接，禁止每次操作 open/close（WAL 初始化 + 文件 IO 开销）。

### R9. 磁盘写入必须在单线程后台 executor
- **事故**：主线程同步写盘在慢速闪存设备上导致切歌掉帧。
- **规则**：历史记录、收藏、队列快照等写入走单线程后台 executor；高频写入加防抖（收藏 400ms）。

---

## 三、卡死类（远程管理浏览器端）

### R10. img 的 onerror 禁止回置 src
- **事故**：`<img onerror="this.src=''">` —— 空 src 被浏览器解析为**当前页面 URL**，把整页 HTML 当图片加载 → 再次失败 → 再次 onerror → 无限请求循环，CPU/网络风暴，浏览器卡死。JS 里 `img.src = ''` 是同一陷阱。
- **规则**：
  - onerror 首次触发即自毁：`onerror="this.onerror=null;this.removeAttribute('src')"`。
  - JS 动态设置封面：无 URL 时 `removeAttribute('src')`，**绝不赋空字符串**。
  - 任何 `onerror` / `catch` 里重新赋值 src 的写法，必须带"只重试一次"守卫。

### R11. 轮询必须有在途标记 + 页面隐藏暂停
- **规则**：
  - 定时轮询（loadPlayer 2s / pollSearch 1.2s / loadStatus 15s）在回调开头检查 `xxxFetching` 标记，慢网响应未回时跳过本轮，防请求堆积。
  - `visibilitychange` 隐藏时暂停轮询与 SSE 重连。

### R12. 高频状态渲染必须签名比对
- **事故**：播放队列每 2 秒整体 `innerHTML` 重建导致浏览器卡顿。
- **规则**：列表渲染前比对内容签名（queueSig），相同只更新局部（当前行高亮），不同才重建 DOM。

### R13. SSE 客户端重连必须防抖
- **规则**：EventSource onerror 后等 30s 再重连；重连时先 close 旧连接；失败降级为轮询。

---

## 四、卡死类（服务端线程模型）

### R14. HTTP 线程池必须即时扩容
- **事故**：固定核心线程 + 有限队列在并发突增时请求堆积；单线程串行处理时一个慢请求阻塞全部。
- **规则**：`SynchronousQueue + max=16`（并发即扩容）；SSE 长连接用 `Semaphore(6)` 限流，超出返回 503 降级轮询。

### R15. 所有 socket 读写必须有超时
- **事故**：SSE 写操作无超时，手机锁屏/休眠后连接停滞，线程永久阻塞。
- **规则**：每帧写带 8 秒 watchdog 强制断开停滞客户端；HTTP handler 里任何网络调用带超时。

### R16. QuickJS 引擎全局串行下的流量分流
- **事故**：播放解析固定走 primary 引擎，被慢源搜索/翻页占住 → 点击播放延迟数十秒。
- **规则**：播放解析/歌词走 `callParallel` 粘性 home 引擎；primary 只承载浏览流量；新增引擎调用类型时先想清楚它会排哪个队的队尾。

### R17. 引擎池状态机禁止单向衰减
- **事故**：`laneBusy` 只减不增，"全忙"状态永久无法解除，新引擎无法创建。
- **规则**：状态标志必须与真实状态同步（在获取/释放两个路径上成对维护），评审时画一遍状态转移。

---

## 五、快速自查命令

- 编译验证：`.\gradlew.bat assembleDebug --console=plain`
- 新增 Lazy 列表时：搜索本文件 R1，确认 key 策略
- 新增 HTTP 接口时：R11 / R14 / R15 三条过一遍
- 新增插件调用时：R4（代数）/ R5（判空）/ R16（队列选择）
