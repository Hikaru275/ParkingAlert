package com.hikaru.parkingalert.util

import android.content.Context
import androidx.annotation.StringRes
import com.hikaru.parkingalert.R
import com.hikaru.parkingalert.data.AppPrefs

enum class PermissionKey {
    SMS,
    NOTIFICATION,
    EXACT_ALARM,
    FULL_SCREEN,
    BATTERY,
    AUTOSTART,
}

data class MissingPermission(
    val key: PermissionKey,
    @StringRes val title: Int,
    @StringRes val description: Int,
)

/**
 * Single source of truth for the setup checklist, shared by the home screen, the
 * twice-daily check and the reminder notification.
 */
object PermissionAudit {

    fun missing(context: Context): List<MissingPermission> = buildList {
        if (!PermissionUtils.hasSms(context)) {
            add(
                MissingPermission(
                    PermissionKey.SMS,
                    R.string.permission_sms,
                    R.string.permission_sms_desc,
                )
            )
        }
        if (!PermissionUtils.hasNotificationPermission(context)) {
            add(
                MissingPermission(
                    PermissionKey.NOTIFICATION,
                    R.string.permission_notification,
                    R.string.permission_notification_desc,
                )
            )
        }
        if (!PermissionUtils.canScheduleExactAlarms(context)) {
            add(
                MissingPermission(
                    PermissionKey.EXACT_ALARM,
                    R.string.permission_exact_alarm,
                    R.string.permission_exact_alarm_desc,
                )
            )
        }
        if (!PermissionUtils.canUseFullScreenIntent(context)) {
            add(
                MissingPermission(
                    PermissionKey.FULL_SCREEN,
                    R.string.permission_fullscreen,
                    R.string.permission_fullscreen_desc,
                )
            )
        }
        if (!PermissionUtils.isIgnoringBatteryOptimizations(context)) {
            add(
                MissingPermission(
                    PermissionKey.BATTERY,
                    R.string.permission_battery,
                    R.string.permission_battery_desc,
                )
            )
        }
        if (OemSettings.needsAutostartGuide(context) &&
            !AppPrefs.autostartConfirmed(context)
        ) {
            add(
                MissingPermission(
                    PermissionKey.AUTOSTART,
                    R.string.permission_autostart,
                    R.string.permission_autostart_desc,
                )
            )
        }
    }
}
