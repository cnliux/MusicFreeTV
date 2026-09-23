package com.tvmusic.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 前台媒体服务：系统播放控制 / 状态栏通知。
 * Media3 的 MediaSessionService 在播放开始后自动以前台服务方式运行并展示通知。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val session = MediaSession.Builder(this, PlayerManager.ensurePlayer()).build()
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}