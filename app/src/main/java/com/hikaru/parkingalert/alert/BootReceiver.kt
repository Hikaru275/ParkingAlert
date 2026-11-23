package com.hikaru.parkingalert.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.service.GuardianService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                if (AppPrefs.guardianEnabled(context)) {
                    GuardianService.start(context)
                }
                AlertCoordinator.restoreAfterBoot(context)
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit

            else -> return
        }

        // Keep the 09:00 / 18:00 audit aligned with the current time zone.
        PermissionCheckScheduler.schedule(context)
    }
}
