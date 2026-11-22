package com.hikaru.parkingalert.alert

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hikaru.parkingalert.R
import com.hikaru.parkingalert.data.AlertStage
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.data.PendingAlert
import com.hikaru.parkingalert.util.NotificationHelper

/**
 * Foreground alert service used by both escalation stages:
 *
 * - VIBRATE: a strong, short vibration-only reminder, no sound.
 * - ALARM: the system alarm ringtone, vibration and a full-screen alarm page.
 *
 * The service starts before the activity so the alert keeps working even when the
 * full-screen launch is blocked by the OEM.
 */
class AlertService : Service() {

    companion object {
        const val ACTION_START = "com.hikaru.parkingalert.service.START_ALERT"
        const val ACTION_SILENCE = "com.hikaru.parkingalert.service.SILENCE_ALERT"
        const val ACTION_STOP = "com.hikaru.parkingalert.service.STOP_ALERT"

        @Volatile
        var isRinging: Boolean = false
            private set

        @Volatile
        var currentStage: AlertStage? = null
            private set

        @Volatile
        var ringEndsAt: Long = 0L
            private set

        /** Set when this alert's current stage has ended, so a recreated screen does not restart it. */
        @Volatile
        var endedAlertId: Long = 0L
            private set

        @Volatile
        var endedAsSilenced: Boolean = false
            private set

        @Volatile
        var endedStage: AlertStage? = null
            private set

        fun hasEnded(alertId: Long, stage: AlertStage): Boolean =
            endedAlertId == alertId && endedStage == stage

        fun start(context: Context, alert: PendingAlert) {
            val intent = Intent(context, AlertService::class.java).apply {
                action = ACTION_START
                AlertIntent.putExtras(this, alert)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }

        fun silence(context: Context): Boolean {
            if (!isRinging) return false
            val intent = Intent(context, AlertService::class.java).setAction(ACTION_SILENCE)
            return runCatching {
                context.startService(intent)
                true
            }.getOrDefault(false)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var currentAlert: PendingAlert? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rampRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val alert = AlertIntent.from(intent)
                if (alert == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sameStage = isRinging &&
                    currentAlert?.id == alert.id &&
                    currentAlert?.stage == alert.stage
                currentAlert = alert
                if (!startForegroundFor(alert)) return START_NOT_STICKY
                if (!sameStage) startStage(alert)
            }

            ACTION_SILENCE -> {
                val alert = currentAlert ?: AppPrefs.pending(this)
                if (alert != null) {
                    silenceInternal(alert)
                } else {
                    stopSelf()
                }
            }

            ACTION_STOP -> stopEverything()

            else -> {
                if (currentAlert == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRinging()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startForegroundFor(alert: PendingAlert): Boolean {
        val isVibration = alert.stage == AlertStage.VIBRATE
        val notification = if (isVibration) {
            NotificationHelper.cancelAlarm(this)
            NotificationHelper.buildVibration(this, alert)
        } else {
            NotificationHelper.cancelVibration(this)
            NotificationHelper.buildAlarm(this, alert)
        }
        val id = if (isVibration) NotificationHelper.ID_VIBRATE else NotificationHelper.ID_ALARM
        val type = when {
            isVibration && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            !isVibration && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, id, notification, type)
            true
        } catch (_: Exception) {
            if (isVibration) {
                NotificationHelper.showVibrationFallback(this, alert)
            } else {
                NotificationHelper.showFallback(this, alert)
            }
            stopSelf()
            false
        }
    }

    private fun startStage(alert: PendingAlert) {
        stopRinging()

        val isVibration = alert.stage == AlertStage.VIBRATE
        val durationSeconds = if (isVibration) {
            AppPrefs.VIBRATION_SECONDS
        } else {
            AppPrefs.ringSeconds(this)
        }
        ringEndsAt = System.currentTimeMillis() + durationSeconds * 1000L
        currentStage = alert.stage
        endedAlertId = 0L
        endedStage = null
        endedAsSilenced = false
        isRinging = true

        wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParkingAlert:Alert")
                ?.apply {
                    setReferenceCounted(false)
                    acquire((durationSeconds + 15L) * 1000L)
                }
        }.getOrNull()

        if (isVibration) {
            startVibration()
        } else {
            playAlarmSound()
            if (AppPrefs.vibrate(this)) startVibration()
            scheduleVolumeRamp()
        }
        scheduleTimeout(alert, durationSeconds, isVibration)
    }

    /**
     * Uses the phone's default alarm ringtone. On MIUI this is the Xiaomi alarm sound
     * selected in the system Clock/Alarm settings. Falls back to the bundled tone.
     */
    private fun playAlarmSound() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        audioManager?.let { manager ->
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }

        val initialVolume = if (AppPrefs.volumeRamp(this)) 0.2f else 1f
        if (playSystemAlarm(attributes, initialVolume)) return
        playBundledAlarm(attributes, initialVolume)
    }

    private fun playSystemAlarm(attributes: AudioAttributes, volume: Float): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return false
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(this@AlertService, uri)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            player?.release()
            player = null
            false
        }
    }

    private fun playBundledAlarm(attributes: AudioAttributes, volume: Float) {
        try {
            val descriptor = resources.openRawResourceFd(R.raw.alarm_tone)
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                descriptor.use {
                    setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
        } catch (_: Exception) {
            player?.release()
            player = MediaPlayer.create(this, R.raw.alarm_tone)?.apply {
                isLooping = true
                setVolume(volume, volume)
                start()
            }
        }
    }

    private fun startVibration() {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator = device
        if (device?.hasVibrator() == true) {
            device.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 800L, 200L, 800L, 200L, 1200L),
                    0,
                )
            )
        }
    }

    private fun scheduleVolumeRamp() {
        if (!AppPrefs.volumeRamp(this)) {
            player?.setVolume(1f, 1f)
            return
        }
        var volume = 0.2f
        val runnable = object : Runnable {
            override fun run() {
                if (!isRinging) return
                volume = (volume + 0.08f).coerceAtMost(1f)
                player?.setVolume(volume, volume)
                if (volume < 1f) {
                    handler.postDelayed(this, 800L)
                }
            }
        }
        rampRunnable = runnable
        handler.postDelayed(runnable, 800L)
    }

    private fun scheduleTimeout(
        alert: PendingAlert,
        durationSeconds: Int,
        vibrationStage: Boolean,
    ) {
        val runnable = Runnable {
            if (!isRinging) return@Runnable
            endedAlertId = alert.id
            endedStage = alert.stage
            endedAsSilenced = false
            stopRinging()
            if (vibrationStage) {
                NotificationHelper.showVibrationEnded(this, alert)
            } else {
                NotificationHelper.showTimeout(this, alert)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        timeoutRunnable = runnable
        handler.postDelayed(runnable, durationSeconds * 1000L)
    }

    private fun silenceInternal(alert: PendingAlert) {
        val vibrationStage = alert.stage == AlertStage.VIBRATE
        endedAlertId = alert.id
        endedStage = alert.stage
        endedAsSilenced = true
        stopRinging()
        if (vibrationStage) {
            NotificationHelper.showVibrationSilenced(this, alert)
        } else {
            NotificationHelper.showSilenced(this, alert)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopEverything() {
        stopRinging()
        NotificationHelper.cancelAlarm(this)
        NotificationHelper.cancelVibration(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRinging() {
        isRinging = false
        currentStage = null
        ringEndsAt = 0L
        rampRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        rampRunnable = null
        timeoutRunnable = null

        player?.let { media ->
            runCatching { if (media.isPlaying) media.stop() }
            runCatching { media.release() }
        }
        player = null

        vibrator?.cancel()
        vibrator = null

        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null

        focusRequest?.let { request ->
            audioManager?.abandonAudioFocusRequest(request)
        }
        focusRequest = null
    }
}
