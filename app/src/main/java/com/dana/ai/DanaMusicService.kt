package com.dana.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import kotlin.random.Random

/**
 * Local-only music player. No internet upload and no access to files other than
 * music indexed by Android's MediaStore, after explicit user permission.
 * Background playback uses an opt-in foreground mediaPlayback service.
 */
class DanaMusicService : Service() {
    companion object {
        const val PLAY_RANDOM = "com.dana.ai.music.RANDOM"
        const val PLAY_NAMED = "com.dana.ai.music.NAMED"
        const val PAUSE = "com.dana.ai.music.PAUSE"
        const val RESUME = "com.dana.ai.music.RESUME"
        const val NEXT = "com.dana.ai.music.NEXT"
        const val PREVIOUS = "com.dana.ai.music.PREVIOUS"
        const val STOP = "com.dana.ai.music.STOP"
        private const val EXTRA_QUERY = "query"
        private const val CHANNEL = "dana_local_music"
        private const val NOTIFICATION_ID = 7310
        @Volatile var running = false
            private set

        fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasMusicPermission(context: Context): Boolean =
            context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED

        /** Accepted for dispatch, not a guarantee that the track has been found/played. */
        fun command(context: Context, action: String, query: String? = null): Boolean {
            if ((action == PLAY_RANDOM || action == PLAY_NAMED) && !hasMusicPermission(context)) return false
            val intent = Intent(context, DanaMusicService::class.java).setAction(action)
                .putExtra(EXTRA_QUERY, query)
            return runCatching {
                if (Build.VERSION.SDK_INT >= 26 && (action == PLAY_RANDOM || action == PLAY_NAMED))
                    context.startForegroundService(intent)
                else context.startService(intent)
            }.isSuccess
        }
    }

    private data class Song(val uri: Uri, val title: String)
    private val songs = mutableListOf<Song>()
    private var index = -1
    private var player: MediaPlayer? = null
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var pausedByFocus = false

    override fun onCreate() {
        super.onCreate()
        running = true
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "پخش موسیقی محلی دانا", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == STOP) {
            stopPlayback()
            stopSelf()
            return START_NOT_STICKY
        }
        // When started by startForegroundService, promote immediately before MediaStore work.
        startInForeground(if (action == PLAY_NAMED || action == PLAY_RANDOM)
            "در حال جست‌وجوی موسیقی…" else "کنترل پخش موسیقی")
        when (action) {
            PLAY_RANDOM, PLAY_NAMED -> {
                if (!hasMusicPermission(this)) {
                    updateNotification("اجازه دسترسی به موسیقی داده نشده")
                    stopSelf()
                } else {
                    if (loadSongs()) {
                        val query = if (action == PLAY_NAMED) intent.getStringExtra(EXTRA_QUERY) else null
                        val chosen = if (query.isNullOrBlank()) Random.nextInt(songs.size) else {
                            val normalized = CommandEngine.normalize(query)
                            songs.indexOfFirst { CommandEngine.normalize(it.title) == normalized }
                                .takeIf { it >= 0 } ?: songs.indexOfFirst {
                                CommandEngine.normalize(it.title).contains(normalized)
                            }
                        }
                        if (chosen >= 0) playAt(chosen)
                        else {
                            updateNotification("آهنگ «${query?.take(50)}» در حافظه پیدا نشد")
                            if (player == null) stopSelf()
                        }
                    } else {
                        updateNotification("موسیقی قابل پخش در حافظه پیدا نشد")
                        if (player == null) stopSelf()
                    }
                }
            }
            PAUSE -> pausePlayback()
            RESUME -> if (player != null) resumePlayback() else if (loadSongs()) playAt(Random.nextInt(songs.size))
            NEXT -> if (songs.isNotEmpty()) playAt((index + 1).mod(songs.size))
            PREVIOUS -> if (songs.isNotEmpty()) playAt((index - 1).mod(songs.size))
        }
        return START_NOT_STICKY
    }

    private fun loadSongs(): Boolean {
        songs.clear()
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
        val success = runCatching {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                while (cursor.moveToNext() && songs.size < 10000) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: continue
                    songs += Song(ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), title)
                }
            }
        }.isSuccess
        return success && songs.isNotEmpty()
    }

    private fun playAt(position: Int) {
        if (position !in songs.indices) return
        stopCurrentPlayer()
        index = position
        val song = songs[position]
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            next.setDataSource(this, song.uri)
            next.setOnPreparedListener { prepared ->
                if (player === prepared) {
                    requestFocus()
                    prepared.start()
                    updateNotification("در حال پخش: ${song.title}")
                }
            }
            next.setOnCompletionListener { finished ->
                if (player === finished && songs.isNotEmpty()) playAt((index + 1).mod(songs.size))
            }
            next.setOnErrorListener { _, _, _ ->
                updateNotification("پخش این فایل ممکن نیست؛ آهنگ دیگری را امتحان کن")
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            stopCurrentPlayer()
            updateNotification("بازکردن فایل «${song.title}» ممکن نشد")
        }
    }

    private fun requestFocus() {
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            pausedByFocus = player?.isPlaying == true
                            if (pausedByFocus) player?.pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f, 0.2f)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            player?.setVolume(1f, 1f)
                            if (pausedByFocus) { pausedByFocus = false; resumePlayback() }
                        }
                    }
                }.build()
        }
        focus?.let { audio.requestAudioFocus(it) }
    }

    private fun pausePlayback() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        updateNotification("موسیقی مکث شد")
    }
    private fun resumePlayback() {
        runCatching { player?.start() }
        updateNotification(songs.getOrNull(index)?.let { "در حال پخش: ${it.title}" } ?: "موسیقی")
    }
    private fun stopCurrentPlayer() {
        player?.let { runCatching { it.reset() }; runCatching { it.release() } }
        player = null
    }
    private fun stopPlayback() {
        stopCurrentPlayer()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        songs.clear()
        index = -1
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun control(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, DanaMusicService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dana)
            .setContentTitle("دانا • موسیقی گوشی")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,
                "قبلی", control(PREVIOUS, 11)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
                "مکث", control(PAUSE, 12)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_play,
                "ادامه", control(RESUME, 13)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,
                "بعدی", control(NEXT, 14)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "توقف", control(STOP, 15)).build())
            .build()
    }
    private fun startInForeground(message: String) {
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification(message),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification(message))
    }
    private fun updateNotification(message: String) {
        // Notification permission is optional for foreground media playback on Android 13+.
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(message)) }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        stopPlayback()
        super.onDestroy()
    }
}
