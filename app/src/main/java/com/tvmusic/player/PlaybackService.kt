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
 * Media3 的 MediaSessionService 在播放开始后自动以前台服务方式运行并展示通知。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val bitmapLoader by lazy { HeaderAwareBitmapLoader() }

    override fun onCreate() {
        super.onCreate()
        val session = MediaSession.Builder(this, PlayerManager.ensurePlayer())
            .setBitmapLoader(bitmapLoader)
            .build()
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

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

        override fun decodeBitmap(data: ByteArray): ListenableFuture<android.graphics.Bitmap> =
            BitmapFuture(background = false) {
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
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
                while (!done) lock.wait()
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
        }
    }
}