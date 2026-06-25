package com.train.ipodclassicemulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.train.ipodclassicemulator.data.repository.LocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service che gestisce la riproduzione della musica locale
 * e pubblica una notifica MediaStyle con controlli play/pausa/avanti/indietro.
 */
class LocalMusicService : Service() {

    companion object {
        const val CHANNEL_ID      = "local_music_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY      = "com.train.ipodclassicemulator.ACTION_PLAY"
        const val ACTION_PAUSE     = "com.train.ipodclassicemulator.ACTION_PAUSE"
        const val ACTION_NEXT      = "com.train.ipodclassicemulator.ACTION_NEXT"
        const val ACTION_PREVIOUS  = "com.train.ipodclassicemulator.ACTION_PREVIOUS"
        const val ACTION_STOP      = "com.train.ipodclassicemulator.ACTION_STOP"

        const val EXTRA_TRACK_TITLE  = "extra_track_title"
        const val EXTRA_TRACK_ARTIST = "extra_track_artist"
        const val EXTRA_TRACK_ALBUM  = "extra_track_album"
        const val EXTRA_ALBUM_ART    = "extra_album_art"
        const val EXTRA_DURATION_MS  = "extra_duration_ms"
        const val EXTRA_CONTENT_URI  = "extra_content_uri"
        const val EXTRA_IS_PLAYING   = "extra_is_playing"
    }

    // ── Binder per comunicazione diretta con ViewModel ─────────────────────
    inner class LocalBinder : Binder() {
        fun getService() = this@LocalMusicService
    }
    private val binder = LocalBinder()

    // ── MediaSession ───────────────────────────────────────────────────────
    private lateinit var mediaSession: MediaSessionCompat

    // ── MediaPlayer ────────────────────────────────────────────────────────
    var mediaPlayer: MediaPlayer? = null
    private var _isPlaying = false
    val isPlaying: Boolean get() = _isPlaying

    // ── Audio Focus ────────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // ── Metadati brano corrente ────────────────────────────────────────────
    private var currentTitle  = ""
    private var currentArtist = ""
    private var currentAlbum  = ""
    private var currentAlbumArtUri: Uri? = null
    private var currentDurationMs = 0L

    // ── Coroutine scope del service ────────────────────────────────────────
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // ── Callback verso ViewModel ───────────────────────────────────────────
    var onPlaybackStateChanged: ((isPlaying: Boolean, positionMs: Long) -> Unit)? = null
    var onTrackCompleted: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    // ── Progress ticker ────────────────────────────────────────────────────
    private var progressJob: Job? = null

    // ── Broadcast receiver per i tasti della notifica ─────────────────────
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PLAY     -> resumePlayback()
                ACTION_PAUSE    -> pausePlayback()
                ACTION_NEXT     -> onNextRequested?.invoke()
                ACTION_PREVIOUS -> onPreviousRequested?.invoke()
                ACTION_STOP     -> stopSelf()
            }
        }
    }

    // Callback navigazione brani (impostati dal ViewModel tramite binding)
    var onNextRequested:     (() -> Unit)? = null
    var onPreviousRequested: (() -> Unit)? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREVIOUS)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Avvia subito in foreground con una notifica placeholder
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        progressJob?.cancel()
        serviceJob.cancel()
        releaseMediaPlayer()
        abandonAudioFocus()
        mediaSession.release()
        try { unregisterReceiver(notificationReceiver) } catch (_: Exception) {}
    }

    // ── MediaSession setup ─────────────────────────────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "LocalMusicSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()     = resumePlayback()
                override fun onPause()    = pausePlayback()
                override fun onSkipToNext()     { onNextRequested?.invoke() }
                override fun onSkipToPrevious() { onPreviousRequested?.invoke() }
                override fun onStop()     = stopSelf()
                override fun onSeekTo(pos: Long) { seekTo(pos) }
            })
            isActive = true
        }
    }

    // ── Riproduzione ───────────────────────────────────────────────────────

    fun playTrack(track: LocalTrack) {
        releaseMediaPlayer()
        requestAudioFocus()

        currentTitle       = track.title
        currentArtist      = track.artist
        currentAlbum       = track.album
        currentAlbumArtUri = track.albumArtUri
        currentDurationMs  = track.durationMs

        updateMediaSessionMetadata(null)

        // Carica album art in background poi aggiorna
        serviceScope.launch {
            val bmp = loadBitmapFromUri(track.albumArtUri)
            updateMediaSessionMetadata(bmp)
            updateNotification(bmp)
        }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            try {
                setDataSource(applicationContext, track.contentUri)
                setOnPreparedListener { mp ->
                    mp.start()
                    _isPlaying = true
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, 0L)
                    onPlaybackStateChanged?.invoke(true, 0L)
                    startProgressTicker()
                }
                setOnCompletionListener {
                    _isPlaying = false
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, currentDurationMs)
                    onPlaybackStateChanged?.invoke(false, currentDurationMs)
                    onTrackCompleted?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    _isPlaying = false
                    onError?.invoke()
                    false
                }
                prepareAsync()
            } catch (e: Exception) {
                Log.e("LocalMusicService", "Errore playTrack", e)
                onError?.invoke()
            }
        }
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _isPlaying = false
        val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, pos)
        onPlaybackStateChanged?.invoke(false, pos)
        serviceScope.launch { updateNotification(null) }
        progressJob?.cancel()
    }

    fun resumePlayback() {
        mediaPlayer?.start()
        _isPlaying = true
        val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, pos)
        onPlaybackStateChanged?.invoke(true, pos)
        serviceScope.launch { updateNotification(null) }
        startProgressTicker()
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
    }

    fun currentPositionMs(): Long = mediaPlayer?.currentPosition?.toLong() ?: 0L

    private fun releaseMediaPlayer() {
        progressJob?.cancel()
        mediaPlayer?.apply {
            setOnPreparedListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            try { stop() } catch (_: Exception) {}
            release()
        }
        mediaPlayer = null
        _isPlaying = false
    }

    // ── Audio Focus ────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { focus ->
                    when (focus) {
                        AudioManager.AUDIOFOCUS_LOSS            -> pausePlayback()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT  -> pausePlayback()
                        AudioManager.AUDIOFOCUS_GAIN            -> resumePlayback()
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    // ── Progress ticker ────────────────────────────────────────────────────

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (true) {
                delay(1000)
                if (!isPlaying) break
                val pos = mediaPlayer?.currentPosition?.toLong() ?: break
                onPlaybackStateChanged?.invoke(true, pos)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, pos)
            }
        }
    }

    // ── MediaSession state & metadata ──────────────────────────────────────

    private fun updatePlaybackState(state: Int, positionMs: Long) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, positionMs, 1f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMediaSessionMetadata(albumArt: Bitmap?) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,  currentAlbum)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
            .apply { albumArt?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
            .build()
        mediaSession.setMetadata(metadata)
    }

    // ── Notifica ───────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Musica in riproduzione",
                NotificationManager.IMPORTANCE_LOW   // IMPORTANCE_LOW = niente suono
            ).apply {
                description = "Controlli riproduzione musica locale"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private suspend fun updateNotification(preloadedBitmap: Bitmap?) {
        val bmp = preloadedBitmap ?: loadBitmapFromUri(currentAlbumArtUri)
        val notification = buildNotification(bmp)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(albumArt: Bitmap? = null): Notification {
        // Intent per aprire l'app toccando la notifica
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionPending(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                this, requestCode, Intent(action).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val prevPending  = actionPending(ACTION_PREVIOUS, 10)
        val playPending  = actionPending(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, 11)
        val nextPending  = actionPending(ACTION_NEXT, 12)

        val playIcon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle.ifBlank { "iPod Classic" })
            .setContentText(
                when {
                    currentArtist.isNotBlank() && currentAlbum.isNotBlank() -> "$currentArtist — $currentAlbum"
                    currentArtist.isNotBlank() -> currentArtist
                    else -> "Musica locale"
                }
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(albumArt)
            .setContentIntent(openAppPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)          // sticky solo durante la riproduzione
            .addAction(android.R.drawable.ic_media_previous, "Precedente", prevPending)
            .addAction(playIcon, if (isPlaying) "Pausa" else "Play", playPending)
            .addAction(android.R.drawable.ic_media_next, "Successivo", nextPending)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)   // prev / play / next
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(actionPending(ACTION_STOP, 13))
            )
            .build()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private suspend fun loadBitmapFromUri(uri: Uri?): Bitmap? = withContext(Dispatchers.IO) {
        uri ?: return@withContext null
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.w("LocalMusicService", "Album art non disponibile", e)
            null
        }
    }
}
