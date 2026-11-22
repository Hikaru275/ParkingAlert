package com.hikaru.parkingalert.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hikaru.parkingalert.data.PendingAlert
import com.hikaru.parkingalert.ui.MainActivity

object AlertScheduler {

    private const val REQUEST_ESCALATION = 200
    private const val REQUEST_SHOW = 201

    fun schedule(context: Context, alert: PendingAlert) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = alert.escalationAt.coerceAtLeast(System.currentTimeMillis() + 500L)

        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_ESCALATION,
            Intent(context, EscalationReceiver::class.java).apply {
                action = "com.hikaru.parkingalert.action.ESCALATE"
                putExtra(AlertIntent.EXTRA_ID, alert.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val showIntent = PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            if (canExact) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
            } else {
                // Still deliver, but Android may defer this while the phone is idle.
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

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_ESCALATION,
            Intent(context, EscalationReceiver::class.java).apply {
                action = "com.hikaru.parkingalert.action.ESCALATE"
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (operation != null) {
            manager.cancel(operation)
            operation.cancel()
        }
    }
}
