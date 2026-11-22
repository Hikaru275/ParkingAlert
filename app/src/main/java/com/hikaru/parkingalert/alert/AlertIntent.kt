package com.hikaru.parkingalert.alert

import android.content.Intent
import com.hikaru.parkingalert.data.AlertStage
import com.hikaru.parkingalert.data.PendingAlert

/** Extras and action names shared by notifications, services and receivers. */
object AlertIntent {

    const val EXTRA_ID = "alert_id"
    const val EXTRA_SENDER = "alert_sender"
    const val EXTRA_BODY = "alert_body"
    const val EXTRA_KEYWORDS = "alert_keywords"
    const val EXTRA_RECEIVED_AT = "alert_received_at"
    const val EXTRA_ESCALATION_AT = "alert_escalation_at"
    const val EXTRA_STAGE = "alert_stage"
    const val EXTRA_RING = "alert_ring"

    const val ACTION_ACK = "com.hikaru.parkingalert.action.ACK"
    const val ACTION_SNOOZE = "com.hikaru.parkingalert.action.SNOOZE"
    const val ACTION_SILENCE = "com.hikaru.parkingalert.action.SILENCE"
    const val ACTION_CANCEL_SNOOZE = "com.hikaru.parkingalert.action.CANCEL_SNOOZE"

    fun putExtras(intent: Intent, alert: PendingAlert): Intent = intent.apply {
        putExtra(EXTRA_ID, alert.id)
        putExtra(EXTRA_SENDER, alert.sender)
        putExtra(EXTRA_BODY, alert.body)
        putStringArrayListExtra(EXTRA_KEYWORDS, ArrayList(alert.keywords))
        putExtra(EXTRA_RECEIVED_AT, alert.receivedAt)
        putExtra(EXTRA_ESCALATION_AT, alert.escalationAt)
        putExtra(EXTRA_STAGE, alert.stage.name)
    }

    fun from(intent: Intent?): PendingAlert? {
        if (intent == null) return null
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id == 0L) return null
        val stage = runCatching {
            AlertStage.valueOf(intent.getStringExtra(EXTRA_STAGE) ?: AlertStage.GENTLE.name)
        }.getOrDefault(AlertStage.GENTLE)
        return PendingAlert(
            id = id,
            sender = intent.getStringExtra(EXTRA_SENDER).orEmpty(),
            body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            keywords = intent.getStringArrayListExtra(EXTRA_KEYWORDS).orEmpty(),
            receivedAt = intent.getLongExtra(EXTRA_RECEIVED_AT, id),
            escalationAt = intent.getLongExtra(EXTRA_ESCALATION_AT, 0L),
            stage = stage,
        )
    }

    fun wantsRing(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_RING, false) == true
}
