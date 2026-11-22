package com.hikaru.parkingalert.alert

import android.content.Context
import com.hikaru.parkingalert.R
import com.hikaru.parkingalert.data.AlertMode
import com.hikaru.parkingalert.data.AlertRecord
import com.hikaru.parkingalert.data.AlertStage
import com.hikaru.parkingalert.data.AlertStatus
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.data.PendingAlert
import com.hikaru.parkingalert.sms.KeywordMatcher
import com.hikaru.parkingalert.util.NotificationHelper

/**
 * Single place that turns a matched SMS into a pending alert, escalation, ack or snooze.
 * Keeping this state machine in one object makes the notification actions and the
 * full-screen activity behave consistently.
 */
object AlertCoordinator {

    @Synchronized
    fun onSmsMatched(
        context: Context,
        sender: String,
        body: String,
        matchedKeywords: List<String>,
        receivedAt: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Boolean {
        if (matchedKeywords.isEmpty()) return false
        if (!force && AppPrefs.shouldSkipDuplicate(context, sender, body)) return false

        if (AlertService.isRinging) {
            addRecordIfEnabled(
                context,
                AlertRecord(
                    id = System.currentTimeMillis(),
                    sender = sender,
                    body = body,
                    keywords = matchedKeywords,
                    receivedAt = receivedAt,
                    status = AlertStatus.PENDING,
                ),
            )
            NotificationHelper.showBusy(context)
            return false
        }

        val now = System.currentTimeMillis()
        val mode = AppPrefs.alertMode(context)
        val escalationAt = when (mode) {
            AlertMode.GENTLE_THEN_ALARM -> now + AppPrefs.GENTLE_DELAY_MS
            AlertMode.ALARM_ONLY -> now
            AlertMode.NOTIFY_ONLY -> 0L
        }
        val pending = PendingAlert(
            id = now,
            sender = sender,
            body = body,
            keywords = matchedKeywords,
            receivedAt = if (receivedAt > 0L) receivedAt else now,
            escalationAt = escalationAt,
            stage = if (mode == AlertMode.ALARM_ONLY) AlertStage.ALARM else AlertStage.GENTLE,
        )

        AlertScheduler.cancel(context)
        NotificationHelper.cancelAlertSurfaces(context)
        AppPrefs.setPending(context, pending)
        addRecordIfEnabled(
            context,
            AlertRecord(
                id = pending.id,
                sender = sender,
                body = body,
                keywords = matchedKeywords,
                receivedAt = pending.receivedAt,
                status = AlertStatus.PENDING,
            ),
        )

        when (mode) {
            AlertMode.GENTLE_THEN_ALARM -> {
                NotificationHelper.showGentle(context, pending)
                AlertScheduler.schedule(context, pending)
            }
            AlertMode.ALARM_ONLY -> startAlarm(context, pending)
            AlertMode.NOTIFY_ONLY -> NotificationHelper.showGentle(context, pending)
        }
        return true
    }

    @Synchronized
    fun escalate(context: Context, alertId: Long) {
        val pending = AppPrefs.pending(context) ?: return
        if (alertId != 0L && pending.id != alertId) return
        when (pending.stage) {
            AlertStage.GENTLE -> startVibration(context, pending)
            AlertStage.VIBRATE, AlertStage.ALARM -> startAlarm(context, pending)
        }
    }

    /**
     * First escalation: a quiet full-screen vibration reminder. The next exact alarm
     * is scheduled for one minute later and will start the audible alarm stage.
     */
    @Synchronized
    private fun startVibration(context: Context, pending: PendingAlert) {
        val active = pending.copy(
            stage = AlertStage.VIBRATE,
            escalationAt = System.currentTimeMillis() + AppPrefs.VIBRATE_DELAY_MS,
        )
        AppPrefs.setPending(context, active)
        NotificationHelper.cancelGentle(context)
        updateRecordStatus(context, active.id, AlertStatus.PENDING)
        AlertScheduler.schedule(context, active)

        try {
            AlertService.start(context, active)
        } catch (_: Exception) {
            NotificationHelper.showVibrationFallback(context, active)
        }
    }

    @Synchronized
    private fun startAlarm(context: Context, pending: PendingAlert) {
        val active = pending.copy(
            stage = AlertStage.ALARM,
            escalationAt = System.currentTimeMillis(),
        )
        AppPrefs.setPending(context, active)
        AlertScheduler.cancel(context)
        NotificationHelper.cancelGentle(context)
        NotificationHelper.cancelVibration(context)
        updateRecordStatus(context, active.id, AlertStatus.PENDING)

        try {
            AlertService.start(context, active)
        } catch (_: Exception) {
            // Some OEMs block foreground-service starts even for exact alarms.
            // A full-screen notification is the best remaining fallback.
            NotificationHelper.showFallback(context, active)
        }
    }

    @Synchronized
    fun acknowledge(context: Context, alertId: Long) {
        val pending = AppPrefs.pending(context)
        if (pending != null && alertId != 0L && pending.id != alertId) return
        if (pending != null) {
            updateRecordStatus(context, pending.id, AlertStatus.ACKNOWLEDGED)
        }
        AppPrefs.clearPending(context)
        AlertScheduler.cancel(context)
        AlertService.stop(context)
        NotificationHelper.cancelAlertSurfaces(context)
    }

    @Synchronized
    fun snooze(context: Context, alertId: Long) {
        val pending = AppPrefs.pending(context) ?: return
        if (alertId != 0L && pending.id != alertId) return

        val triggerAt = System.currentTimeMillis() + AppPrefs.SNOOZE_MINUTES * 60_000L
        val updated = pending.copy(escalationAt = triggerAt, stage = AlertStage.ALARM)
        AppPrefs.setPending(context, updated)
        updateRecordStatus(context, updated.id, AlertStatus.SNOOZED)

        AlertScheduler.cancel(context)
        AlertService.stop(context)
        NotificationHelper.showSnoozed(context, updated, triggerAt)
        AlertScheduler.schedule(context, updated)
    }

    @Synchronized
    fun silence(context: Context, alertId: Long) {
        val pending = AppPrefs.pending(context) ?: return
        if (alertId != 0L && pending.id != alertId) return
        AlertService.silence(context)
    }

    @Synchronized
    fun cancelSnooze(context: Context, alertId: Long) {
        val pending = AppPrefs.pending(context)
        if (pending == null) {
            NotificationHelper.cancelSnoozed(context)
            return
        }
        if (alertId != 0L && pending.id != alertId) return
        AppPrefs.clearPending(context)
        AlertScheduler.cancel(context)
        AlertService.stop(context)
        NotificationHelper.showCancelled(context)
        updateRecordStatus(context, pending.id, AlertStatus.CANCELLED)
    }

    @Synchronized
    fun restoreAfterBoot(context: Context) {
        val pending = AppPrefs.pending(context) ?: return
        val now = System.currentTimeMillis()
        if (pending.escalationAt > now + 1000L) {
            AlertScheduler.schedule(context, pending)
            NotificationHelper.showRestored(context, pending, pending.escalationAt)
        } else {
            AppPrefs.clearPending(context)
            AlertScheduler.cancel(context)
            updateRecordStatus(context, pending.id, AlertStatus.MISSED)
            NotificationHelper.showMissed(context)
        }
    }

    @Synchronized
    fun startTest(context: Context): Boolean {
        if (AlertService.isRinging) return false
        val body = context.getString(R.string.test_body)
        val keywords = AppPrefs.keywords(context)
        val match = KeywordMatcher.match(
            body = body,
            keywords = keywords,
            strictMode = false,
            ignoreMarketing = false,
        )
        val matched = match.matchedKeywords.ifEmpty { keywords.take(1) }
        return onSmsMatched(
            context = context,
            sender = context.getString(R.string.test_sender),
            body = body,
            matchedKeywords = matched,
            receivedAt = System.currentTimeMillis(),
            force = true,
        )
    }

    private fun addRecordIfEnabled(context: Context, record: AlertRecord) {
        if (AppPrefs.saveHistory(context)) {
            AppPrefs.addRecord(context, record)
        }
    }

    private fun updateRecordStatus(context: Context, id: Long, status: AlertStatus) {
        if (!AppPrefs.saveHistory(context)) return
        AppPrefs.updateRecordStatus(context, id, status)
    }
}
