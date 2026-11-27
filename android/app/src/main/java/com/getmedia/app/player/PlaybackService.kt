package com.getmedia.app.player

import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放。
 *
 * 用 MediaSessionService 而不是自己管 Notification：通知栏控制、
 * 蓝牙耳机按键、锁屏控件都由框架接管。
 */
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var hadQueue = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { save() }
    }
    private fun save(synchronous: Boolean = false) {
        val current = player ?: return
        val snapshot = playbackSnapshot(current)
        if (snapshot != null) hadQueue = true
        if (snapshot != null || hadQueue) PlaybackMemory(this).save(snapshot, synchronous)
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        session = MediaSession.Builder(this, AdjacentSkipPlayer(exo)).build()
        exo.addListener(listener)
        scope.launch { while (isActive) { delay(1000); save() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        save(synchronous = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        save(synchronous = true)
        player?.removeListener(listener)
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }
}

/**
 * 让通知栏、锁屏、蓝牙的「上一首 / 下一首」一次就换歌。
 *
 * Media3 的 `seekToPrevious()` 自带一条旧规则：当前这首播过 3 秒以上时先退回本曲开头，
 * 再点一次才切上一首（`maxSeekToPreviousPositionMs` 默认 3000，且 1.3.1 的 Builder 不开放配置）。
 * 通知栏那颗按钮走的正是这条命令，于是用户看到的就是「点一下从头开始，点两下才换歌」。
 *
 * 这里不接管命令回调（回调里返回成功会继续执行默认行为，返回失败又会把错误报给通知栏），
 * 而是在播放器外面包一层，把这两个方法直接换成"切到相邻项"。其余全部原样转发。
 */
private class AdjacentSkipPlayer(player: Player) : ForwardingPlayer(player) {
    override fun seekToPrevious() = seekToPreviousMediaItem()
    override fun seekToNext() = seekToNextMediaItem()
}
