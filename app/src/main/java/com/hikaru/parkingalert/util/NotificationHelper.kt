package com.hikaru.parkingalert.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hikaru.parkingalert.R
import com.hikaru.parkingalert.alert.AlertActivity
import com.hikaru.parkingalert.alert.AlertActionReceiver
import com.hikaru.parkingalert.alert.AlertIntent
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.data.PendingAlert
import com.hikaru.parkingalert.ui.MainActivity

object NotificationHelper {

    const val CHANNEL_GENTLE = "gentle"
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_FALLBACK = "alarm_fallback"
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_GUARDIAN = "guardian"

    const val ID_GENTLE = 1001
    const val ID_ALARM = 1002
    const val ID_TIMEOUT = 1003
    const val ID_SNOOZED = 1004
    const val ID_BUSY = 1005
    const val ID_MISSED = 1006
    const val ID_FALLBACK = 1007
    const val ID_CANCELLED = 1008
    const val ID_VIBRATE = 1009
    const val ID_VIBRATE_ENDED = 1010
    const val ID_GUARDIAN = 1100

    private const val REQUEST_FULLSCREEN = 10
    private const val REQUEST_CONTENT = 11
    private const val REQUEST_MAIN = 12

    // ---------------------------------------------------------------------
    // Channels
    // ---------------------------------------------------------------------

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createIfMissing(manager, guardianChannel(context))
        createIfMissing(manager, statusChannel(context))
        createIfMissing(manager, gentleChannel(context))
        createIfMissing(manager, alarmChannel(context))
        createIfMissing(manager, fallbackChannel(context))
    }

    /**
     * Channel sound/vibration/DND settings are immutable after creation, so the three
     * alert channels are recreated when the user changes those preferences.
     */
    fun refreshChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(CHANNEL_GENTLE)
        manager.deleteNotificationChannel(CHANNEL_ALARM)
        manager.deleteNotificationChannel(CHANNEL_FALLBACK)
        ensureChannels(context)
    }

    private fun createIfMissing(manager: NotificationManager, channel: NotificationChannel) {
        if (manager.getNotificationChannel(channel.id) == null) {
            manager.createNotificationChannel(channel)
        }
    }

    private fun guardianChannel(context: Context) = NotificationChannel(
        CHANNEL_GUARDIAN,
        context.getString(R.string.notification_channel_guardian),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_guardian_desc)
        setShowBadge(false)
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
    }

    private fun statusChannel(context: Context) = NotificationChannel(
        CHANNEL_STATUS,
        context.getString(R.string.notification_channel_status),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.notification_channel_status_desc)
        setShowBadge(true)
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    private fun gentleChannel(context: Context) = NotificationChannel(
        CHANNEL_GENTLE,
        context.getString(R.string.notification_channel_gentle),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_gentle_desc)
        setShowBadge(true)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        setSound(soundUri(context, R.raw.gentle_chime), attributes)
        enableVibration(AppPrefs.vibrate(context))
        vibrationPattern = longArrayOf(0L, 220L, 120L, 220L)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDndSafely(context, this)
    }

    private fun alarmChannel(context: Context) = NotificationChannel(
        CHANNEL_ALARM,
        context.getString(R.string.notification_channel_alarm),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_alarm_desc)
        setShowBadge(true)
        // The foreground service plays the looping alarm itself.
        setSound(null, null)
        enableVibration(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDndSafely(context, this)
    }

    private fun fallbackChannel(context: Context) = NotificationChannel(
        CHANNEL_FALLBACK,
        context.getString(R.string.notification_channel_fallback),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.notification_channel_fallback_desc)
        setShowBadge(true)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: soundUri(context, R.raw.alarm_tone)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        setSound(alarmUri, attributes)
        enableVibration(AppPrefs.vibrate(context))
        vibrationPattern = longArrayOf(0L, 600L, 250L, 600L, 250L, 900L)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        setBypassDndSafely(context, this)
    }

    private fun setBypassDndSafely(context: Context, channel: NotificationChannel) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val allowed = manager.isNotificationPolicyAccessGranted &&
            AppPrefs.bypassDnd(context)
        runCatching { channel.setBypassDnd(allowed) }
    }

    private fun soundUri(context: Context, resourceId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resourceId")

    // ---------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------

    fun buildGentle(context: Context, alert: PendingAlert): Notification {
        val sender = displaySender(context, alert.sender)
        val snippet = alert.body.replace('\n', ' ').take(90)
        return NotificationCompat.Builder(context, CHANNEL_GENTLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .setContentTitle(context.getString(R.string.notification_gentle_title))
            .setContentText(context.getString(R.string.notification_gentle_text, sender, snippet))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_gentle_text, sender, alert.body))
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.receivedAt)
            .setShowWhen(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent(context, alert, ring = false))
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .build()
    }

    fun buildVibration(context: Context, alert: PendingAlert): Notification {
        val sender = displaySender(context, alert.sender)
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.alertAccent))
            .setContentTitle(context.getString(R.string.notification_vibration_title))
            .setContentText(context.getString(R.string.notification_vibration_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$sender：${alert.body}")
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.receivedAt)
            .setShowWhen(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent(context, alert, ring = true))
            .setFullScreenIntent(fullScreenIntent(context, alert), true)
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .addAction(0, context.getString(R.string.notification_stop_vibration), actionIntent(context, AlertIntent.ACTION_SILENCE, alert.id))
            .build()
    }

    fun buildVibrationEnded(context: Context, alert: PendingAlert): Notification =
        buildVibrationQuiet(
            context,
            alert,
            R.string.notification_vibration_ended_title,
            R.string.notification_vibration_ended_text,
        )

    fun buildVibrationSilenced(context: Context, alert: PendingAlert): Notification =
        buildVibrationQuiet(
            context,
            alert,
            R.string.notification_vibration_silenced_title,
            R.string.notification_vibration_silenced_text,
        )

    private fun buildVibrationQuiet(
        context: Context,
        alert: PendingAlert,
        titleRes: Int,
        textRes: Int,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.alertAccent))
            .setContentTitle(context.getString(titleRes))
            .setContentText(
                context.getString(textRes, TimeFormat.clock(alert.escalationAt))
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.receivedAt)
            .setShowWhen(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context, alert, ring = false))
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .build()

    fun buildAlarm(context: Context, alert: PendingAlert): Notification {
        val sender = displaySender(context, alert.sender)
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.alertAccent))
            .setContentTitle(context.getString(R.string.notification_alarm_title))
            .setContentText(context.getString(R.string.notification_alarm_text, "$sender：${alert.body}"))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_alarm_text, "$sender：${alert.body}"))
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.receivedAt)
            .setShowWhen(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent(context, alert, ring = true))
            .setFullScreenIntent(fullScreenIntent(context, alert), true)
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .addAction(0, context.getString(R.string.notification_silence), actionIntent(context, AlertIntent.ACTION_SILENCE, alert.id))
            .build()
    }

    fun buildFallback(context: Context, alert: PendingAlert): Notification {
        val sender = displaySender(context, alert.sender)
        return NotificationCompat.Builder(context, CHANNEL_FALLBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.alertAccent))
            .setContentTitle(context.getString(R.string.notification_alarm_title))
            .setContentText(context.getString(R.string.notification_alarm_text, "$sender：${alert.body}"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.receivedAt)
            .setShowWhen(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, alert, ring = true))
            .setFullScreenIntent(fullScreenIntent(context, alert), true)
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .build()
    }

    fun buildSilenced(context: Context, alert: PendingAlert): Notification =
        NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.alertAccent))
            .setContentTitle(context.getString(R.string.notification_silenced_title))
            .setContentText(context.getString(R.string.notification_silenced_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(alert.receivedAt)
            .setShowWhen(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context, alert, ring = false))
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .build()

    fun buildTimeout(context: Context, alert: PendingAlert): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.statusAmber))
            .setContentTitle(context.getString(R.string.ring_timeout_notification_title))
            .setContentText(context.getString(R.string.ring_timeout_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, alert, ring = false))
            .addAction(0, context.getString(R.string.notification_ack), actionIntent(context, AlertIntent.ACTION_ACK, alert.id))
            .addAction(0, context.getString(R.string.notification_snooze), actionIntent(context, AlertIntent.ACTION_SNOOZE, alert.id))
            .build()

    fun buildSnoozed(context: Context, alert: PendingAlert, triggerAt: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_snoozed_title))
            .setContentText(
                context.getString(R.string.notification_snoozed_text, TimeFormat.clock(triggerAt))
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .addAction(
                0,
                context.getString(R.string.notification_action_cancel),
                actionIntent(context, AlertIntent.ACTION_CANCEL_SNOOZE, alert.id)
            )
            .build()

    fun buildBusy(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_busy_title))
            .setContentText(context.getString(R.string.notification_busy_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .build()

    fun buildMissed(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_missed_title))
            .setContentText(context.getString(R.string.notification_missed_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .build()

    fun buildCancelled(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_cancelled_title))
            .setContentText(context.getString(R.string.notification_cancelled_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))
            .build()

    fun buildRestored(context: Context, alert: PendingAlert, triggerAt: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_restored_title))
            .setContentText(
                context.getString(R.string.notification_restored_text, TimeFormat.clock(triggerAt))
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, alert, ring = false))
            .addAction(
                0,
                context.getString(R.string.notification_action_cancel),
                actionIntent(context, AlertIntent.ACTION_CANCEL_SNOOZE, alert.id)
            )
            .build()

    fun buildGuardian(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_GUARDIAN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_guardian_title))
            .setContentText(context.getString(R.string.notification_guardian_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(mainIntent(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    // ---------------------------------------------------------------------
    // Show / cancel
    // ---------------------------------------------------------------------

    fun showGentle(context: Context, alert: PendingAlert) {
        notify(context, ID_GENTLE, buildGentle(context, alert))
    }

    fun showAlarm(context: Context, alert: PendingAlert) {
        cancel(context, ID_GENTLE)
        cancelVibration(context)
        notify(context, ID_ALARM, buildAlarm(context, alert))
    }

    fun showFallback(context: Context, alert: PendingAlert) {
        cancel(context, ID_GENTLE)
        cancelVibration(context)
        notify(context, ID_FALLBACK, buildFallback(context, alert))
    }

    fun showVibrationFallback(context: Context, alert: PendingAlert) {
        cancel(context, ID_GENTLE)
        notify(context, ID_VIBRATE, buildVibration(context, alert))
    }

    fun showVibrationEnded(context: Context, alert: PendingAlert) {
        cancel(context, ID_VIBRATE)
        notify(context, ID_VIBRATE_ENDED, buildVibrationEnded(context, alert))
    }

    fun showVibrationSilenced(context: Context, alert: PendingAlert) {
        cancel(context, ID_VIBRATE)
        notify(context, ID_VIBRATE_ENDED, buildVibrationSilenced(context, alert))
    }

    fun showSilenced(context: Context, alert: PendingAlert) {
        notify(context, ID_ALARM, buildSilenced(context, alert))
    }

    fun showTimeout(context: Context, alert: PendingAlert) {
        cancel(context, ID_ALARM)
        notify(context, ID_TIMEOUT, buildTimeout(context, alert))
    }

    fun showSnoozed(context: Context, alert: PendingAlert, triggerAt: Long) {
        cancelAlertSurfaces(context)
        notify(context, ID_SNOOZED, buildSnoozed(context, alert, triggerAt))
    }

    fun showBusy(context: Context) {
        notify(context, ID_BUSY, buildBusy(context))
    }

    fun showMissed(context: Context) {
        notify(context, ID_MISSED, buildMissed(context))
    }

    fun showRestored(context: Context, alert: PendingAlert, triggerAt: Long) {
        cancelAlertSurfaces(context)
        notify(context, ID_SNOOZED, buildRestored(context, alert, triggerAt))
    }

    fun showCancelled(context: Context) {
        cancelAlertSurfaces(context)
        notify(context, ID_CANCELLED, buildCancelled(context))
    }

    fun cancelGentle(context: Context) = cancel(context, ID_GENTLE)

    fun cancelAlarm(context: Context) = cancel(context, ID_ALARM)

    fun cancelVibration(context: Context) {
        cancel(context, ID_VIBRATE)
        cancel(context, ID_VIBRATE_ENDED)
    }

    fun cancelSnoozed(context: Context) = cancel(context, ID_SNOOZED)

    fun cancelAlertSurfaces(context: Context) {
        listOf(
            ID_GENTLE,
            ID_ALARM,
            ID_TIMEOUT,
            ID_SNOOZED,
            ID_BUSY,
            ID_FALLBACK,
            ID_CANCELLED,
            ID_VIBRATE,
            ID_VIBRATE_ENDED,
        ).forEach {
            cancel(context, it)
        }
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    // ---------------------------------------------------------------------
    // Pending intents
    // ---------------------------------------------------------------------

    private fun fullScreenIntent(context: Context, alert: PendingAlert): PendingIntent =
        activityIntent(context, alert, ring = true, REQUEST_FULLSCREEN)

    private fun contentIntent(context: Context, alert: PendingAlert, ring: Boolean): PendingIntent =
        activityIntent(context, alert, ring, REQUEST_CONTENT)

    private fun activityIntent(
        context: Context,
        alert: PendingAlert,
        ring: Boolean,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            AlertIntent.putExtras(this, alert)
            putExtra(AlertIntent.EXTRA_RING, ring)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String, alertId: Long): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlertIntent.EXTRA_ID, alertId)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mainIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_MAIN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun displaySender(context: Context, sender: String): String =
        sender.ifBlank { context.getString(R.string.history_sender_unknown) }
}
