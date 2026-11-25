package app.voyahmusic.player.playback

import android.app.Notification
import android.app.Notification.MediaStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.voyahmusic.player.domain.Track
import app.voyahmusic.player.ui.PlayerActivity
import app.voyahmusic.shared.R
import app.voyahmusic.shared.data.MusicPreferences

@UnstableApi
class PlaybackService : Service() {
    data class State(
        val id: String?,
        val wantsPlay: Boolean,
        val playing: Boolean,
        val shuffle: Boolean,
        val repeat: Int,
        val failed: Boolean
    )

    private val binder: LocalBinder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val listeners: MutableSet<Runnable> = HashSet<Runnable>()
    private val checkpoint = Runnable {
        savePosition()
        scheduleCheckpoint()
    }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var open: PendingIntent? = null
    private var resume: SharedPreferences? = null
    private var started = false
    private var foreground = false
    private var syncing = false

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService {
            return this@PlaybackService
        }
    }

    override fun onCreate() {
        super.onCreate()
        resume = getSharedPreferences("playback", MODE_PRIVATE)
        player = ExoPlayer.Builder(this).build()
        player!!.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
        )
        player!!.setHandleAudioBecomingNoisy(true)
        player!!.setWakeMode(C.WAKE_MODE_LOCAL)
        player!!.setShuffleModeEnabled(resume!!.getBoolean("shuffle", false))
        player!!.setRepeatMode(resume!!.getInt("repeat", Player.REPEAT_MODE_OFF))
        player!!.addListener(object : Player.Listener {
            override fun onEvents(source: Player, events: Player.Events) {
                if (!syncing) publish(
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                            || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
                            || events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                player!!.pause()
            }
        })
        open = PendingIntent.getActivity(
            this, 0, Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession(this, "Voyah Music")
        session!!.setSessionActivity(open)
        session!!.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                play()
            }

            override fun onPause() {
                player!!.pause()
            }

            override fun onSeekTo(position: Long) {
                seek(position)
            }

            override fun onSkipToNext() {
                next()
            }

            override fun onSkipToPrevious() {
                previous()
            }

            override fun onStop() {
                player!!.pause()
            }
        })
        val localized: Context = MusicPreferences.localized(this)
        getSystemService<NotificationManager?>(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                PlaybackService.CHANNEL,
                localized.getString(R.string.now_playing),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        val action = if (intent == null) null else intent.getAction()
        if (PlaybackService.PREVIOUS == action) previous()
        if (PlaybackService.TOGGLE == action) toggle()
        if (PlaybackService.NEXT == action) next()
        publish(false)
        return START_NOT_STICKY
    }

    fun setTracks(tracks: List<Track>) {
        if (player!!.getMediaItemCount() == 0 && tracks.isEmpty()) return
        syncing = true
        try {
            if (player!!.getMediaItemCount() == 0) {
                val last = resume!!.getString("track", "")
                var start = 0
                for (i in tracks.indices) {
                    if (tracks.get(i).id == last) start = i
                    player!!.addMediaItem(PlaybackService.item(tracks.get(i)))
                }
                val position =
                    if (tracks.get(start).id == last) resume!!.getLong("position", 0) else 0
                player!!.seekTo(start, position)
                player!!.prepare()
            } else {
                val ids: MutableSet<String?> = HashSet<String?>()
                for (track in tracks) ids.add(track.id)
                for (i in player!!.getMediaItemCount() - 1 downTo 0) {
                    if (!ids.contains(player!!.getMediaItemAt(i).mediaId)) player!!.removeMediaItem(
                        i
                    )
                }
                for (i in tracks.indices) {
                    val track = tracks.get(i)
                    val existing = indexOf(track.id, i)
                    if (existing == -1) player!!.addMediaItem(
                        i,
                        PlaybackService.item(track)
                    )
                    else if (existing != i) player!!.moveMediaItem(existing, i)
                }
                if (tracks.isEmpty()) player!!.pause()
            }
        } finally {
            syncing = false
        }
        publish(true)
    }

    private fun indexOf(id: String?, start: Int): Int {
        for (i in start..<player!!.getMediaItemCount()) {
            if (player!!.getMediaItemAt(i).mediaId == id) return i
        }
        return -1
    }

    fun select(id: String?) {
        val index = indexOf(id, 0)
        if (index < 0) return
        if (index != player!!.getCurrentMediaItemIndex()) player!!.seekToDefaultPosition(index)
        play()
    }

    fun toggle() {
        if (state().wantsPlay) player!!.pause()
        else play()
    }

    private fun play() {
        if (player!!.getCurrentMediaItem() == null) return
        if (!started) startService(Intent(this, PlaybackService::class.java))
        if (player!!.getPlaybackState() == Player.STATE_ENDED) player!!.seekToDefaultPosition()
        if (player!!.getPlaybackState() == Player.STATE_IDLE) player!!.prepare()
        player!!.play()
    }

    fun previous() {
        player!!.seekToPrevious()
    }

    fun next() {
        if (player!!.hasNextMediaItem()) player!!.seekToNextMediaItem()
    }

    fun seek(position: Long) {
        if (duration() > 0) player!!.seekTo(position.coerceIn(0L, duration()))
    }

    fun shuffle() {
        player!!.setShuffleModeEnabled(!player!!.getShuffleModeEnabled())
        resume!!.edit().putBoolean("shuffle", player!!.getShuffleModeEnabled()).apply()
    }

    fun repeat() {
        val next = when (player!!.getRepeatMode()) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        player!!.setRepeatMode(next)
        resume!!.edit().putInt("repeat", next).apply()
    }

    fun position(): Long {
        return player!!.currentPosition.coerceAtLeast(0L)
    }

    fun duration(): Long {
        return player!!.duration.coerceAtLeast(0L)
    }

    fun hasNext(): Boolean {
        return player!!.hasNextMediaItem()
    }

    fun state(): State {
        val item = player!!.getCurrentMediaItem()
        val wantsPlay = item != null && player!!.getPlayWhenReady()
                && player!!.getPlaybackState() != Player.STATE_ENDED && player!!.getPlayerError() == null
        return State(
            if (item == null) "" else item.mediaId,
            wantsPlay,
            player!!.isPlaying(),
            player!!.getShuffleModeEnabled(),
            player!!.getRepeatMode(),
            player!!.getPlayerError() != null
        )
    }

    fun observe(listener: Runnable?) {
        listeners.add(listener!!)
    }

    fun removeObserver(listener: Runnable?) {
        listeners.remove(listener!!)
    }

    private fun publish(checkpoint: Boolean) {
        val state = state()
        if (checkpoint) savePosition()
        scheduleCheckpoint()
        val current = player!!.getCurrentMediaItem()
        session!!.setActive(current != null && (started || state.wantsPlay))
        if (current != null) {
            val metadata = MediaMetadata.Builder()
                .putString(
                    MediaMetadata.METADATA_KEY_TITLE,
                    PlaybackService.text(current.mediaMetadata.title)
                )
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    PlaybackService.text(current.mediaMetadata.artist)
                )
                .putString(
                    MediaMetadata.METADATA_KEY_ALBUM,
                    PlaybackService.text(current.mediaMetadata.albumTitle)
                )
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build()
            session!!.setMetadata(metadata)
            val status = if (state.failed) PlaybackState.STATE_ERROR else if (state.playing)
                PlaybackState.STATE_PLAYING
            else
                if (state.wantsPlay) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            var actions =
                (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
                        or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            if (hasNext()) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            session!!.setPlaybackState(
                PlaybackState.Builder().setActions(actions)
                    .setState(status, position(), if (state.playing) 1f else 0f).build()
            )
        }
        if (started && state.wantsPlay) {
            startForeground(PlaybackService.NOTIFICATION, notification(current!!))
            foreground = true
        } else if (!state.wantsPlay) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            if (started) {
                started = false
                stopSelf()
                session!!.setActive(false)
            }
        }
        for (listener in listeners) listener.run()
    }

    @Suppress("deprecation")
    private fun notification(current: MediaItem): Notification {
        val localized: Context = MusicPreferences.localized(this)
        return Notification.Builder(this, PlaybackService.CHANNEL)
            .setSmallIcon(R.drawable.ic_music_notification)
            .setContentTitle(current.mediaMetadata.title)
            .setContentText(current.mediaMetadata.artist)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_previous,
                localized.getString(R.string.previous),
                command(PlaybackService.PREVIOUS)
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                localized.getString(R.string.pause),
                command(PlaybackService.TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                localized.getString(R.string.next),
                command(PlaybackService.NEXT)
            )
            .setStyle(
                MediaStyle().setMediaSession(session!!.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun command(action: String?): PendingIntent? {
        return PendingIntent.getService(
            this, 0, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleCheckpoint() {
        main.removeCallbacks(checkpoint)
        if (player!!.isPlaying()) main.postDelayed(checkpoint, 15000)
    }

    private fun savePosition() {
        val item = player!!.getCurrentMediaItem()
        if (item != null) resume!!.edit().putString("track", item.mediaId)
            .putLong("position", position()).apply()
    }

    override fun onDestroy() {
        savePosition()
        main.removeCallbacks(checkpoint)
        listeners.clear()
        player!!.release()
        session!!.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "music_playback"
        private const val NOTIFICATION = 1
        private const val PREVIOUS = "previous"
        private const val TOGGLE = "toggle"
        private const val NEXT = "next"

        private fun item(track: Track): MediaItem {
            return MediaItem.Builder().setMediaId(track.id).setUri(Uri.fromFile(track.file))
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                        .build()
                )
                .build()
        }

        private fun text(value: CharSequence?): String {
            return if (value == null) "" else value.toString()
        }
    }
}
