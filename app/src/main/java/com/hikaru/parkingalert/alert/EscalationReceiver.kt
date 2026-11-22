package com.hikaru.parkingalert.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hikaru.parkingalert.data.AppPrefs

/** Exact-alarm receiver that upgrades the light notification to the full-screen alarm. */
class EscalationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlertIntent.EXTRA_ID, 0L)
        val pending = AppPrefs.pending(context) ?: return
        if (id != 0L && pending.id != id) return
        AlertCoordinator.escalate(context, pending.id)
    }
}
