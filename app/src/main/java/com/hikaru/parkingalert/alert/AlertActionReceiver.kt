package com.hikaru.parkingalert.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the notification action buttons without opening the activity. */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlertIntent.EXTRA_ID, 0L)
        when (intent.action) {
            AlertIntent.ACTION_ACK -> AlertCoordinator.acknowledge(context, id)
            AlertIntent.ACTION_SNOOZE -> AlertCoordinator.snooze(context, id)
            AlertIntent.ACTION_SILENCE -> AlertCoordinator.silence(context, id)
            AlertIntent.ACTION_CANCEL_SNOOZE -> AlertCoordinator.cancelSnooze(context, id)
        }
    }
}
