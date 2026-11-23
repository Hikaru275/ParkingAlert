package com.hikaru.parkingalert.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules the 09:00 and 18:00 local-time permission audit. A single one-shot alarm
 * is used and rescheduled by the receiver, which keeps the behavior predictable across
 * reboots and time-zone changes without a polling service.
 */
object PermissionCheckScheduler {

    const val ACTION_CHECK = "com.hikaru.parkingalert.action.PERMISSION_CHECK"
    private const val REQUEST_CODE = 300

    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = PermissionCheckTimes.nextTriggerAt()

        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, PermissionCheckReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (_: SecurityException) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (_: IllegalArgumentException) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        }
    }

}
