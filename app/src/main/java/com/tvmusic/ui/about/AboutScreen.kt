package com.tvmusic.ui.about

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvmusic.BuildConfig
import com.tvmusic.ui.components.DialogTextButton
import com.tvmusic.ui.components.ModalCard
import com.tvmusic.ui.components.tvFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 关于页：开源声明、免责声明与检查更新。 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var dialogVisible by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 64.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo 占位：渐变圆形
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("♪", color = MaterialTheme.colorScheme.onPrimary, fontSize = 42.sp)
            }
            Text(
                "MusicFree TV",
                fontSize = 26.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            UpdateButton(
                state = update,
                onClick = {
                    when (val s = update) {
                        is UpdateState.Checking -> Unit
                        is UpdateState.Downloading -> dialogVisible = true
                        is UpdateState.Ready -> launchInstaller(context, s.version)
                        else -> {
                            update = UpdateState.Checking
                            scope.launch {
                                val res = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                                update = when {
                                    res == null -> UpdateState.Failed("所有线路均不可达，请检查网络后重试")
                                    isNewerVersion(res.version, BuildConfig.VERSION_NAME) -> {
                                        // 必须显式打开弹窗：Found 态按钮文案与 Idle 相同，
                                        // 漏掉 dialogVisible 会导致检测成功却毫无可见反馈
                                        dialogVisible = true
                                        UpdateState.Found(res.version)
                                    }
                                    else -> UpdateState.UpToDate
                                }
                            }
                        }
                    }
                }
            )
            UpdateStatusText(update)

            Section("开源声明") {
                Text(
                    "本项目为开源软件，基于 MusicFree 插件生态构建，遵循相应开源许可证发布。" +
                        "源码可自由获取、修改与分发；欢迎社区贡献与反馈。",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                )
            }
            Section("免责声明") {
                Text(
                    "本软件仅供个人学习与技术研究使用，不提供任何音视频内容。" +
                        "所有内容由第三方插件从其来源平台获取，版权归原作者与平台所有。" +
                    "请勿将本软件用于任何商业用途或侵犯他人权益的行为；" +
                    "使用者应自行遵守所在地法律法规，因使用本软件产生的一切后果由使用者自行承担。",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                )
            }
            Section("致谢") {
                Text(
                    "感谢 MusicFree 项目及其插件作者社区。",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                )
            }
        }

        // 弹层：发现新版本 / 下载进度 / 下载失败（统一 ModalCard + 动效；
        // 放在滚动列外层的 Box 内，遮罩才能盖满整个页面）
        val found = update as? UpdateState.Found
        val downloading = update as? UpdateState.Downloading
        val dlFailed = update as? UpdateState.DownloadFailed
        // 三个更新弹框互斥显示，共用一个初始焦点目标（不传 initialFocus 时
        // ModalCard 回退到不可点击的容器，OK 键无反应）
        val updateDialogFocus = remember { androidx.compose.ui.focus.FocusRequester() }

        AnimatedVisibility(
            visible = found != null && dialogVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            ModalCard(
                title = "发现新版本",
                subtitle = "v${found?.version}（当前 v${BuildConfig.VERSION_NAME}）",
                onDismiss = { dialogVisible = false },
                initialFocus = updateDialogFocus,
                bottomBar = {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    DialogTextButton("取消", { dialogVisible = false })
                    DialogTextButton(
                        "下载更新",
                        onClick = {
                            dialogVisible = false
                            found?.let {
                                update = UpdateState.Downloading(it.version, 0)
                                UpdateChecker.downloadApkAsync(scope, context, it.version, onState = { s -> update = s })
                            }
                        },
                        background = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.focusRequester(updateDialogFocus)
                    )
                }
            ) {}
        }
        AnimatedVisibility(
            visible = downloading != null && dialogVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            ModalCard(
                title = "正在下载更新",
                subtitle = "v${downloading?.version} · ${downloading?.progress ?: 0}%",
                onDismiss = { dialogVisible = false },
                initialFocus = updateDialogFocus,
                bottomBar = {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    DialogTextButton(
                        "后台下载",
                        { dialogVisible = false },
                        modifier = Modifier.focusRequester(updateDialogFocus)
                    )
                }
            ) {}
        }
        AnimatedVisibility(
            visible = dlFailed != null && dialogVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f)
        ) {
            ModalCard(
                title = "下载失败",
                subtitle = dlFailed?.message,
                onDismiss = { dialogVisible = false },
                initialFocus = updateDialogFocus,
                bottomBar = {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    DialogTextButton("取消", { update = UpdateState.Idle })
                    DialogTextButton(
                        "重试",
                        onClick = {
                            dialogVisible = false
                            dlFailed?.let {
                                update = UpdateState.Downloading(it.version, 0)
                                UpdateChecker.downloadApkAsync(scope, context, it.version, onState = { s -> update = s })
                            }
                        },
                        background = MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.focusRequester(updateDialogFocus)
                    )
                }
            ) {}
        }
    }
}

/** 检查更新按钮：随状态切换文案。 */
@Composable
private fun UpdateButton(state: UpdateState, onClick: () -> Unit) {
    val label = when (state) {
        is UpdateState.Idle, is UpdateState.Failed, is UpdateState.DownloadFailed -> "检查更新"
        is UpdateState.Checking -> "正在检查…"
        is UpdateState.Downloading -> "下载中 ${state.progress}%"
        is UpdateState.Ready -> "安装更新 v${state.version}"
        is UpdateState.UpToDate, is UpdateState.Found -> "检查更新"
    }
    Box(
        modifier = Modifier
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary)
            .tvFocus(shapeOverride = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 10.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimary, fontSize = 15.sp)
    }
}

/** 按钮下方的一行状态提示。 */
@Composable
private fun UpdateStatusText(state: UpdateState) {
    val msg = when (state) {
        is UpdateState.UpToDate -> "已是最新版本"
        is UpdateState.Failed -> "检查失败：${state.message}"
        is UpdateState.DownloadFailed -> "下载失败：${state.message}"
        else -> return
    }
    Text(
        msg,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 10.dp)
    )
}

// ---------------- 检查更新 ----------------

/** 更新流程状态。Found 之后走下载；Ready 时按钮直接调起安装器。 */
private sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Found(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
    data class Downloading(val version: String, val progress: Int) : UpdateState
    data class Ready(val version: String) : UpdateState
    data class DownloadFailed(val version: String, val message: String) : UpdateState
}

private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
        .split(".").map { it.trim().toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** 协程下载（IO 调度），完成后自动调起安装器；进度/结果经 onState 回写 UI 状态。 */
private fun UpdateChecker.downloadApkAsync(
    scope: CoroutineScope,
    context: Context,
    version: String,
    onState: (UpdateState) -> Unit
) {
    // 原裸 Thread 改为协程：统一调度/异常/取消，避免跨线程写 Compose 状态
    scope.launch(Dispatchers.IO) {
        try {
            val file = downloadApk(context, version) { p -> onState(UpdateState.Downloading(version, p)) }
            if (file != null) {
                onState(UpdateState.Ready(version))
                launchInstaller(context, version)
            } else {
                // 区分安全校验失败与网络失败：校验失败给出更明确的提示
                val msg = if (UpdateChecker.lastVerifyFailed)
                    "安全校验未通过（文件可能被篡改），已取消安装"
                else "网络异常或下载中断，请重试"
                onState(UpdateState.DownloadFailed(version, msg))
            }
        } catch (e: Exception) {
            android.util.Log.w("AboutScreen", "downloadApkAsync failed", e)
            onState(UpdateState.DownloadFailed(version, "下载失败，请稍后重试"))
        }
    }
}

private fun launchInstaller(context: Context, version: String) {
    val dir = context.getExternalFilesDir("update") ?: File(context.filesDir, "update")
    val file = File(dir, "MusicFreeTV-$version.apk")
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // startActivity 切主线程调起（下载线程直接调用在部分 ROM 上不稳定）
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // 无安装器（极少见）：保留 Ready 状态，按钮仍可再次调起
        }
    }
}

private const val REPO = "cnliux/MusicFreeTV"

/**
 * 更新检测与下载。
 * 版本源线路：jsDelivr（fastly.jsdelivr.net/gh/user/repo@main/...）→ 各代理前缀拼接
 * raw 的 build.gradle.kts（发版流程会把 versionName bump 提交回 main，与最新 Release
 * tag 一致）→ GitHub 直连兜底（raw + releases/latest API 的 tag_name）。
 * 下载线路：下载前并行探测全部镜像（Range 0-1 小请求取响应最快者），
 * 冠军线路优先下载，失败时按其余线路兜底。
 */
private object UpdateChecker {

    /** 校验失败标记：区分"安全校验未通过"与"网络失败"，供 UI 给出准确提示。 */
    @Volatile
    var lastVerifyFailed: Boolean = false
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val dlClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val GRADLE_URL =
        "https://raw.githubusercontent.com/$REPO/main/app/build.gradle.kts"

    /** jsDelivr 的 raw 代理走独立 URL 形态（/gh/user/repo@branch/path），不支持 releases 下载。 */
    private val jsdelivrRaw =
        "https://fastly.jsdelivr.net/gh/${REPO.replace('/', '@')}@main/app/build.gradle.kts"

    /** 空前缀 = 直连。 */
    private val rawPrefixes = listOf(
        "https://wget.la/",
        "https://gh.catmak.name/",
        "https://cdn.gh-proxy.org/",
        "https://g.blfrp.cn/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://github.moeyy.xyz/",
        ""
    )
    private val dlPrefixes = listOf(
        "https://wget.la/",
        "https://gh.catmak.name/",
        "https://cdn.gh-proxy.org/",
        "https://g.blfrp.cn/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://github.moeyy.xyz/",
        ""
    )

    /** 取最新发布版本号（不含 v 前缀）；全部线路失败返回 null。 */
    fun fetchLatest(): LatestInfo? {
        try {
            val body = fetchString(jsdelivrRaw)
            val m = body?.let { Regex("versionName\\s*=\\s*\"(\\d+(?:\\.\\d+)+)\"").find(it) }
            if (m != null) return LatestInfo(m.groupValues[1], "CDN")
        } catch (_: Exception) {
        }
        for (prefix in rawPrefixes) {
            try {
                val body = fetchString(prefix + GRADLE_URL) ?: continue
                val m = Regex("versionName\\s*=\\s*\"(\\d+(?:\\.\\d+)+)\"").find(body) ?: continue
                return LatestInfo(m.groupValues[1], if (prefix.isEmpty()) "GitHub" else "CDN")
            } catch (_: Exception) {
                // 换下一条线路
            }
        }
        // 兜底：releases/latest API（raw 不可达但 API 直连可达时）
        try {
            val body = fetchString("https://api.github.com/repos/$REPO/releases/latest")
            val tag = body?.let { JSONObject(it).optString("tag_name") }
            if (!tag.isNullOrBlank()) return LatestInfo(tag.removePrefix("v"), "GitHub")
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * 并行探测全部下载线路（Range 0-1 小请求），返回响应最快的可用前缀；
     * 全部失败返回 null（调用方退回顺序尝试）。探测跑在独立短超时客户端上。
     */
    private fun probeFastestPrefix(githubUrl: String): String? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(dlPrefixes.size)
        return try {
            val futures = dlPrefixes.map { prefix ->
                pool.submit(java.util.concurrent.Callable<Long?> {
                    val t0 = System.currentTimeMillis()
                    val req = Request.Builder()
                        .url(prefix + githubUrl)
                        .header("Range", "bytes=0-1")
                        .header("User-Agent", "MusicFreeTV-Update")
                        .build()
                    probeClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@Callable null
                        System.currentTimeMillis() - t0
                    }
                })
            }
            var best: String? = null
            var bestMs = Long.MAX_VALUE
            dlPrefixes.forEachIndexed { i, prefix ->
                // 各探测并行执行，这里只是按序收割结果；单条最多等 8 秒
                val ms = try { futures[i].get(8, TimeUnit.SECONDS) } catch (_: Exception) { null }
                if (ms != null && ms < bestMs) {
                    bestMs = ms
                    best = prefix
                }
            }
            best
        } finally {
            pool.shutdownNow()
        }
    }

    /** 下载 release APK；progress 按百分比回调。失败返回 null。 */
    fun downloadApk(context: Context, version: String, onProgress: (Int) -> Unit): File? {
        lastVerifyFailed = false
        val githubUrl = "https://github.com/$REPO/releases/download/v$version/app-release.apk"
        val dir = context.getExternalFilesDir("update") ?: File(context.filesDir, "update")
        if (!dir.exists()) dir.mkdirs()
        // 清理旧版本安装包，避免占满存储
        dir.listFiles()?.forEach { if (it.name != "MusicFreeTV-$version.apk") it.delete() }
        val target = File(dir, "MusicFreeTV-$version.apk")
        val tmp = File(dir, "MusicFreeTV-$version.apk.tmp")

        // 探测出的最快线路打头，其余线路保序兜底（含直连）
        val fastest = probeFastestPrefix(githubUrl)
        val ordered = if (fastest == null) dlPrefixes
        else listOf(fastest) + dlPrefixes.filter { it != fastest }

        for (prefix in ordered) {
            try {
                tmp.delete()
                val req = Request.Builder()
                    .url(prefix + githubUrl)
                    .header("User-Agent", "MusicFreeTV-Update")
                    .build()
                dlClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    val total = body.contentLength()
                    var written = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (total > 0) {
                                    val pct = (written * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                    if (total <= 0 || written < total) {
                        tmp.delete()
                        return@use
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    // 安全校验（H3）：下载产物必须过完整性 + 签名两道关才允许安装
                    // 1. SHA-256 与 GitHub Release 官方摘要比对（取不到官方摘要则跳过该项，不阻断）
                    val official = fetchOfficialSha256(version)
                    if (official != null) {
                        val actual = sha256Of(target)
                        if (!actual.equals(official, ignoreCase = true)) {
                            android.util.Log.w("AboutScreen", "apk sha256 mismatch: official=$official actual=$actual")
                            target.delete()
                            lastVerifyFailed = true
                            return null
                        }
                    }
                    // 2. 签名与当前包一致性（防替换为不同签名的包）
                    if (!signatureMatches(context, target)) {
                        android.util.Log.w("AboutScreen", "apk signature mismatch")
                        target.delete()
                        lastVerifyFailed = true
                        return null
                    }
                    return target
                }
            } catch (_: Exception) {
                // 换下一条线路
            }
        }
        tmp.delete()
        return null
    }

    private fun fetchString(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "MusicFreeTV-Update")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    /**
     * 从 GitHub Release API 取指定版本 APK 的官方 SHA-256 摘要（digest 字段）。
     * 走 HTTPS API，作为下载产物的完整性基准。取不到返回 null（调用方决定降级策略）。
     */
    fun fetchOfficialSha256(version: String): String? {
        return try {
            val body = fetchString("https://api.github.com/repos/$REPO/releases/tags/v$version")
                ?: return null
            val assets = JSONObject(body).optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") != "app-release.apk") continue
                // GitHub 资产 digest 形如 "sha256:xxxx"
                val digest = a.optString("digest", "")
                val hex = digest.substringAfter("sha256:", "").trim()
                if (hex.length == 64) return hex.lowercase()
            }
            null
        } catch (e: Exception) {
            android.util.Log.w("AboutScreen", "fetchOfficialSha256: ${e.message}")
            null
        }
    }

    /** 计算文件的 SHA-256（十六进制小写）。 */
    fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验下载 APK 的签名与当前应用签名是否一致（防止替换为不同签名的包）。
     * API 28+ 用 GET_SIGNING_CERTIFICATES，旧版用 GET_SIGNATURES。
     */
    fun signatureMatches(context: Context, apkFile: File): Boolean {
        return try {
            val pm = context.packageManager
            val pkg = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return false
            val newSig = pkg.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return false
            val cur = pm.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            val curSig = cur.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return false
            newSig.contentEquals(curSig)
        } catch (e: Exception) {
            android.util.Log.w("AboutScreen", "signatureMatches: ${e.message}")
            false
        }
    }
}

private data class LatestInfo(val version: String, val via: String)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp)
    ) {
        Text(
            title,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}
