package com.tvmusic.player

import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台媒体服务：系统播放控制 / 状态栏通知。
 * 本服务由 PlayerManager 在首次播放提交时通过进程内 MediaController 绑定拉起
 * （MediaSessionService 无客户端连接就不会创建实例，也不会显示通知/进前台）；
 * 会话激活且起播后由 Media3 自动以前台服务方式运行并展示通知。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val bitmapLoader by lazy { HeaderAwareBitmapLoader() }

    override fun onCreate() {
        super.onCreate()
        // 兜底：进程被系统拉起重建且 PlayerManager 尚未就绪时不要崩进程
        // （窗口已通过 Application 同步 init 关闭，此处防御未来回归）——
        // 停服务即可，用户下次播放时 PlayerManager 会重新绑定拉起本服务。
        val player = try {
            PlayerManager.ensurePlayer()
        } catch (_: IllegalStateException) {
            android.util.Log.w("PlaybackService", "PlayerManager not ready on service create; stopSelf")
            stopSelf()
            return
        }
        val session = MediaSession.Builder(this, player)
            .setBitmapLoader(bitmapLoader)
            .build()
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * M4：用户从最近任务划掉应用时的处理——先落盘播放快照（供下次「继续播放」），
     * 再停止前台服务。否则通知可能残留、恢复语义只能靠 onStop 兜底。
     */
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        PlayerManager.flushResumeNow()
        // 未在播放时直接停掉服务；正在播放则保持（TV 场景划掉应用后继续听是常见预期）
        val player = PlayerManager.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    /**
     * 通知栏封面加载器：用 PlayerManager 记录的音频请求头下载封面，
     * 这样封面 URL 依赖 Referer 等认证头时通知栏也能正常显示。
     */
    private class HeaderAwareBitmapLoader : BitmapLoader {

        override fun supportsMimeType(mimeType: String): Boolean =
            mimeType.startsWith("image/")

        // 通知栏封面不需要全尺寸，按最长边 512px 两步解码，避免大图浪费内存
        override fun decodeBitmap(data: ByteArray): ListenableFuture<android.graphics.Bitmap> =
            BitmapFuture(background = false) { decodeSampled(data, 512) }

        /** 两步解码：先读尺寸再按 inSampleSize 缩放（与 PlayerManager.decodeArtwork 策略一致）。 */
        private fun decodeSampled(data: ByteArray, maxEdge: Int): android.graphics.Bitmap? {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<android.graphics.Bitmap> =
            BitmapFuture(background = true) {
                PlayerManager.loadArtworkBitmap(uri.toString())
            }
    }

    /**
     * 极简 ListenableFuture 实现（项目未引入完整 guava，media3 仅依赖接口）。
     * 工作线程结束、或完成之后注册监听时，监听都会恰好执行一次。
     */
    private class BitmapFuture(
        background: Boolean,
        private val work: () -> android.graphics.Bitmap?
    ) : ListenableFuture<android.graphics.Bitmap> {

        /** 由 done 与 pending 列表组成，访问一律加锁。 */
        private val lock = Object()
        private var done = false
        private val pending = mutableListOf<Pair<Runnable, Executor>>()
        private var result: android.graphics.Bitmap? = null

        init {
            val task = Runnable {
                result = try {
                    work()
                } catch (_: Exception) {
                    null
                }
                transition()
            }
            if (background) LOADER_EXECUTOR.execute(task) else task.run()
        }

        private fun transition() {
            val toFire: List<Pair<Runnable, Executor>>
            synchronized(lock) {
                if (done) return
                done = true
                toFire = pending.toList()
                // 唤醒所有在 get() 上等待的线程，否则无超时等待只能靠虚假唤醒返回（可能永久挂起）
                lock.notifyAll()
            }
            toFire.forEach { (r, e) -> safeRun(r, e) }
        }

        override fun addListener(listener: Runnable, executor: Executor) {
            var fireNow = false
            synchronized(lock) {
                if (done) fireNow = true else pending.add(listener to executor)
            }
            if (fireNow) safeRun(listener, executor)
        }

        private fun safeRun(listener: Runnable, executor: Executor) {
            try {
                executor.execute(listener)
            } catch (_: Exception) {
                // 监听线程异常不影响封面加载主流程
            }
        }

        override fun isDone(): Boolean = synchronized(lock) { done }

        override fun get(): android.graphics.Bitmap? {
            synchronized(lock) {
                // 带超时的等待：work 抛异常已兜底为 null 并 transition，但若解码/下载
                // 卡死（网络半开连接），无超时的 wait() 会把媒体会话线程挂住。
                var waitedMs = 0L
                while (!done && waitedMs < GET_TIMEOUT_MS) {
                    val slice = minOf(2000L, GET_TIMEOUT_MS - waitedMs)
                    lock.wait(slice)
                    waitedMs += slice
                }
                if (!done) {
                    android.util.Log.w("PlaybackService", "BitmapFuture.get timeout")
                    return null
                }
            }
            return result
        }

        override fun get(timeout: Long, unit: TimeUnit): android.graphics.Bitmap? {
            synchronized(lock) {
                if (!done) {
                    unit.timedWait(lock, timeout)
                    // Future 契约：超时未完成必须抛 TimeoutException，不能静默返回 null
                    if (!done) throw java.util.concurrent.TimeoutException("bitmap load timeout")
                }
                return result
            }
        }

        override fun isCancelled(): Boolean = false

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        companion object {
            /** 封面下载专用后台线程（一次一张，量级很低）。 */
            private val LOADER_EXECUTOR = Executors.newSingleThreadExecutor()
            /** get() 总超时：封面加载不应挂死媒体会话线程。 */
            private const val GET_TIMEOUT_MS = 30_000L
        }
    }
}