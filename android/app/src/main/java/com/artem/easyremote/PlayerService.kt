package com.artem.easyremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.media.VolumeProvider

/** What the player service needs from the app: set by [AppViewModel], which owns the connection. */
object RemoteHub {
    @Volatile var controller: RemoteController? = null
    @Volatile var settings: SettingsStore? = null
    @Volatile var pcName: String = ""

    /** Leaves the remote screen and closes the connection (the notification's "Disconnect"). */
    @Volatile var disconnect: (() -> Unit)? = null
}

/**
 * The "player" in the notification shade and on the lock screen while a PC is connected: rewind, play/pause,
 * forward and volume. It is a media-style notification backed by a media session, so it also shows in the
 * quick-settings media area, answers headset buttons, and, with "Phone volume buttons control the PC" on,
 * takes the phone's hardware volume keys even when the screen is off.
 *
 * It runs as a foreground service so that the connection survives while the app is in the background; it
 * stops itself when the connection is closed or lost for good.
 */
class PlayerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private var remoteVolume = false

    private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // the first version of the channel did not show on the lock screen; channel settings cannot be
        // changed after creation, so it was replaced by a new one
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Remote player", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls for the connected PC"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val hub = RemoteHub
        val controller = hub.controller
        val settings = hub.settings
        session = MediaSession(this, "EasyRemote").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { RemoteHub.controller?.media(Proto.MEDIA_PLAY_PAUSE) }
                override fun onPause() { RemoteHub.controller?.media(Proto.MEDIA_PLAY_PAUSE) }
                override fun onSkipToNext() { RemoteHub.controller?.key(Proto.KEY_RIGHT) }
                override fun onSkipToPrevious() { RemoteHub.controller?.key(Proto.KEY_LEFT) }
                override fun onFastForward() { RemoteHub.controller?.key(Proto.KEY_RIGHT) }
                override fun onRewind() { RemoteHub.controller?.key(Proto.KEY_LEFT) }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
                    )
                    // "playing": it makes the system treat this session as the active one, so the volume keys reach it
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            isActive = true
        }
        // a foreground service must show its notification within seconds of being started
        startForegroundCompat(buildNotification(RemoteHub.pcName, ConnState.Connecting(1), null, settings?.volumeKeys?.value ?: true))

        if (controller == null || settings == null) {
            stopSelf() // the app process was restarted: nothing to control any more
            return
        }
        scope.launch {
            combine(controller.state, controller.volume, settings.volumeKeys) { st, vol, keys -> Triple(st, vol, keys) }
                .collect { (st, vol, keys) ->
                    if (st is ConnState.Failed || st is ConnState.Idle) {
                        stopSelf()
                        return@collect
                    }
                    applyVolumeRouting(keys, vol)
                    session?.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, RemoteHub.pcName)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, statusText(st, vol))
                            .build(),
                    )
                    getSystemService(NotificationManager::class.java).notify(ID, buildNotification(RemoteHub.pcName, st, vol, keys))
                }
        }
    }

    /** With phone volume buttons on, the session is a remote-volume session: the hardware keys step the PC's volume. */
    private fun applyVolumeRouting(keys: Boolean, vol: VolumeInfo?) {
        val s = session ?: return
        if (keys) {
            val current = ((vol?.percent ?: 0) + 2) / STEP
            val p = volumeProvider
            if (!remoteVolume || p == null) {
                val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX_STEPS, current) {
                    override fun onAdjustVolume(direction: Int) {
                        if (direction != 0) RemoteHub.controller?.volumeSteps(if (direction > 0) 1 else -1)
                    }

                    override fun onSetVolumeTo(volume: Int) {
                        val delta = volume - currentVolume
                        if (delta != 0) RemoteHub.controller?.volumeSteps(delta)
                    }
                }
                volumeProvider = provider
                s.setPlaybackToRemote(provider)
                remoteVolume = true
            } else {
                p.currentVolume = current
            }
        } else if (remoteVolume) {
            s.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            volumeProvider = null
            remoteVolume = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = RemoteHub.controller
        when (intent?.action) {
            ACTION_REWIND -> c?.key(Proto.KEY_LEFT)
            ACTION_FORWARD -> c?.key(Proto.KEY_RIGHT)
            ACTION_PLAY_PAUSE -> c?.media(Proto.MEDIA_PLAY_PAUSE)
            ACTION_VOLUME_DOWN -> c?.volumeSteps(-1)
            ACTION_VOLUME_UP -> c?.volumeSteps(1)
            ACTION_DISCONNECT -> RemoteHub.disconnect?.invoke()
            // the notification was swiped away (Android 14+ allows that for ongoing ones): put it back
            ACTION_REPOST -> lastNotification?.let { getSystemService(NotificationManager::class.java).notify(ID, it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            isActive = false
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun statusText(st: ConnState, vol: VolumeInfo?): String = when (st) {
        is ConnState.Ready -> when {
            vol == null -> "Connected"
            vol.muted -> "Connected · muted"
            else -> "Connected · volume ${vol.percent}%"
        }
        else -> "Reconnecting…"
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, n)
        }
    }

    private fun action(icon: Int, title: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            act.hashCode(),
            Intent(this, PlayerService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()
    }

    private fun buildNotification(name: String, st: ConnState, vol: VolumeInfo?, phoneVolumeKeys: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(name.ifBlank { "EasyRemote" })
            .setContentText(statusText(st, vol))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_np_rewind, "Rewind", ACTION_REWIND))
            .addAction(action(R.drawable.ic_np_play_pause, "Play or pause", ACTION_PLAY_PAUSE))
            .addAction(action(R.drawable.ic_np_forward, "Forward", ACTION_FORWARD))
        if (phoneVolumeKeys) {
            // the phone's own volume buttons do the volume, so the fourth button ends the connection instead
            b.addAction(action(R.drawable.ic_np_close, "Disconnect", ACTION_DISCONNECT))
        } else {
            b.addAction(action(R.drawable.ic_np_volume_down, "Volume down", ACTION_VOLUME_DOWN))
            b.addAction(action(R.drawable.ic_np_volume_up, "Volume up", ACTION_VOLUME_UP))
        }
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let { style.setMediaSession(it) }
        b.setStyle(style)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        b.setCategory(Notification.CATEGORY_TRANSPORT)
        b.setDeleteIntent(
            PendingIntent.getService(
                this,
                ACTION_REPOST.hashCode(),
                Intent(this, PlayerService::class.java).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        lastNotification = n
        return n
    }

    companion object {
        private const val CHANNEL = "player_v2"
        private const val OLD_CHANNEL = "player"
        private const val ID = 7
        private const val MAX_STEPS = 20
        private const val STEP = 5

        const val ACTION_REWIND = "com.artem.easyremote.REWIND"
        const val ACTION_FORWARD = "com.artem.easyremote.FORWARD"
        const val ACTION_PLAY_PAUSE = "com.artem.easyremote.PLAY_PAUSE"
        const val ACTION_VOLUME_DOWN = "com.artem.easyremote.VOLUME_DOWN"
        const val ACTION_VOLUME_UP = "com.artem.easyremote.VOLUME_UP"
        const val ACTION_DISCONNECT = "com.artem.easyremote.DISCONNECT"
        const val ACTION_REPOST = "com.artem.easyremote.REPOST"

        /** Starts (or refreshes) the player. Only from the foreground, and only if notifications are allowed. */
        fun start(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, PlayerService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }
}
