package com.hikaru.parkingalert.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.service.GuardianService
import com.hikaru.parkingalert.util.NotificationHelper
import com.hikaru.parkingalert.util.PermissionAudit

/** Receives the 09:00 / 18:00 audit and reschedules the next one. */
class PermissionCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        PermissionCheckScheduler.schedule(context)

        // Re-arm the optional guardian service if it was removed by the OEM.
        if (AppPrefs.guardianEnabled(context)) {
            GuardianService.start(context)
        }

        val missing = PermissionAudit.missing(context)
        if (missing.isEmpty()) {
            NotificationHelper.cancelPermissionReminder(context)
        } else {
            NotificationHelper.showPermissionReminder(
                context,
                missing.map { context.getString(it.title) },
            )
        }
    }
}
