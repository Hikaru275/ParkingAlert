package com.hikaru.parkingalert.alert

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hikaru.parkingalert.R
import com.hikaru.parkingalert.data.AlertStage
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.data.PendingAlert
import com.hikaru.parkingalert.databinding.ActivityAlertBinding
import com.hikaru.parkingalert.util.TimeFormat

/**
 * Full-screen alert page used by the vibration and audible alarm stages. The foreground
 * service owns vibration/sound; this screen only presents information and actions.
 */
class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private val handler = Handler(Looper.getMainLooper())
    private var alert: PendingAlert? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var countdownRunnable: Runnable? = null
    private var silenced = false
    private var ringRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureLockScreenWindow()
        applyInsets()

        onBackPressedDispatcher.addCallback(this) {
            Toast.makeText(this@AlertActivity, R.string.alert_back_hint, Toast.LENGTH_SHORT).show()
        }

        binding.ackButton.setOnClickListener {
            val current = alert ?: return@setOnClickListener
            AlertCoordinator.acknowledge(this, current.id)
            Toast.makeText(this, R.string.alert_acked_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.snoozeButton.setOnClickListener {
            val current = alert ?: return@setOnClickListener
            AlertCoordinator.snooze(this, current.id)
            Toast.makeText(this, R.string.alert_snoozed_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.silenceButton.setOnClickListener {
            val current = alert ?: return@setOnClickListener
            AlertCoordinator.silence(this, current.id)
            silenced = true
            stopPulse()
            stopCountdown()
            val isVibration = current.stage == AlertStage.VIBRATE
            binding.alertCountdown.text = getString(
                if (isVibration) R.string.silenced_vibration else R.string.silenced
            )
            binding.silenceButton.isEnabled = false
            Toast.makeText(
                this,
                if (isVibration) R.string.silenced_vibration else R.string.silenced,
                Toast.LENGTH_SHORT,
            ).show()
        }

        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopPulse()
        stopCountdown()
        silenced = false
        binding.silenceButton.isEnabled = true
        bind(intent)
    }

    override fun onDestroy() {
        stopPulse()
        stopCountdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun configureLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun bind(intent: Intent?) {
        val fromIntent = AlertIntent.from(intent)
        if (fromIntent == null) {
            finish()
            return
        }
        // A notification for an older stage can be tapped after the alert has already
        // escalated. Always prefer the current pending stage so the UI cannot downgrade.
        val stored = AppPrefs.pending(this)
        val hasPending = stored != null && stored.id == fromIntent.id
        val current = if (hasPending) stored!! else fromIntent
        alert = current
        ringRequested = hasPending &&
            (AlertIntent.wantsRing(intent) || AlertService.isRinging)

        val isVibration = current.stage == AlertStage.VIBRATE
        binding.alertHeadline.setText(
            if (isVibration) R.string.alert_headline_vibrate else R.string.alert_headline_alarm
        )
        binding.silenceButton.setText(
            if (isVibration) R.string.silence_vibration else R.string.silence
        )
        binding.alertTime.text = getString(R.string.alert_time, TimeFormat.full(current.receivedAt))
        binding.alertSender.text = getString(
            R.string.alert_sender,
            current.sender.ifBlank { getString(R.string.history_sender_unknown) },
        )
        binding.alertKeywords.text = getString(
            R.string.alert_keywords,
            current.keywords.joinToString(" · "),
        )
        binding.alertBody.text = current.body

        if (isVibration) {
            bindVibrationStage(current)
        } else {
            bindAlarmStage(current)
        }
    }

    private fun bindVibrationStage(current: PendingAlert) {
        startCountdownToAlarm(current)

        if (!ringRequested) {
            binding.silenceButton.visibility = View.GONE
            return
        }

        if (AlertService.hasEnded(current.id, current.stage)) {
            silenced = AlertService.endedAsSilenced
            binding.alertCountdown.text = getString(
                if (silenced) R.string.silenced_vibration else R.string.alert_vibration_ended
            )
            binding.silenceButton.visibility = View.GONE
            stopCountdown()
            return
        }

        val started = ensureStageRunning(current)
        if (started) {
            startPulse()
            binding.silenceButton.visibility = View.VISIBLE
        } else {
            binding.alertCountdown.text = getString(R.string.service_restricted)
            binding.silenceButton.visibility = View.GONE
        }
    }

    private fun bindAlarmStage(current: PendingAlert) {
        if (!ringRequested) {
            binding.alertCountdown.text = getString(R.string.alert_not_ringing)
            binding.silenceButton.visibility = View.GONE
            return
        }

        if (AlertService.hasEnded(current.id, current.stage)) {
            silenced = AlertService.endedAsSilenced
            binding.alertCountdown.text = getString(
                if (silenced) R.string.silenced else R.string.countdown_done
            )
            binding.silenceButton.visibility = View.GONE
            return
        }

        val started = ensureStageRunning(current)
        if (started) {
            startPulse()
            startCountdown()
            binding.silenceButton.visibility = View.VISIBLE
        } else {
            binding.alertCountdown.text = getString(R.string.service_restricted)
            binding.silenceButton.visibility = View.GONE
        }
    }

    private fun ensureStageRunning(current: PendingAlert): Boolean {
        if (AlertService.isRinging) {
            if (AlertService.currentStage == current.stage) return true
            // Only allow an upward transition; never restart vibration over a running alarm.
            if (current.stage != AlertStage.ALARM) return false
        }
        if (AlertService.hasEnded(current.id, current.stage)) return false
        return try {
            AlertService.start(this, current)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        val animator = ObjectAnimator.ofPropertyValuesHolder(
            binding.alertIconContainer,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
        ).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        pulseAnimator = animator
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.alertIconContainer.scaleX = 1f
        binding.alertIconContainer.scaleY = 1f
    }

    private fun startCountdownToAlarm(current: PendingAlert) {
        stopCountdown()
        val end = current.escalationAt
        val runnable = object : Runnable {
            override fun run() {
                if (silenced) return
                val remaining = ((end - System.currentTimeMillis() + 999L) / 1000L)
                    .coerceAtLeast(0L)
                if (remaining > 0L) {
                    binding.alertCountdown.text = getString(R.string.countdown_to_alarm, remaining)
                    handler.postDelayed(this, 1000L)
                } else {
                    binding.alertCountdown.text = getString(R.string.countdown_to_alarm_done)
                    stopPulse()
                    binding.silenceButton.visibility = View.GONE
                }
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun startCountdown() {
        stopCountdown()
        val now = System.currentTimeMillis()
        val fallbackEnd = now + AppPrefs.ringSeconds(this) * 1000L
        val end = if (AlertService.ringEndsAt > now) AlertService.ringEndsAt else fallbackEnd

        val runnable = object : Runnable {
            override fun run() {
                if (silenced) return
                val remaining = ((end - System.currentTimeMillis() + 999L) / 1000L)
                    .coerceAtLeast(0L)
                if (remaining > 0L) {
                    binding.alertCountdown.text = getString(R.string.countdown, remaining)
                    handler.postDelayed(this, 1000L)
                } else {
                    binding.alertCountdown.text = getString(R.string.countdown_done)
                    stopPulse()
                    binding.silenceButton.visibility = View.GONE
                }
            }
        }
        countdownRunnable = runnable
        handler.post(runnable)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }
}
