package com.hikaru.parkingalert.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Best-effort shortcuts into the vendor settings that matter for background SMS
 * delivery. There is no public API for these pages, so every intent is optional.
 */
object OemSettings {

    fun openAutoStart(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val candidates = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            )
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") ||
                manufacturer.contains("realme") -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.oppoguardelf", "com.coloros.powermgr.startup.StartupAppListActivity"),
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"),
            )
            manufacturer.contains("samsung") -> listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            )
            manufacturer.contains("meizu") -> listOf(
                component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
            )
            else -> emptyList()
        }

        for (intent in candidates) {
            if (start(context, intent)) return true
        }
        return openAppDetails(context)
    }

    fun openAppDetails(context: Context): Boolean =
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )

    fun openNotificationSettings(context: Context): Boolean =
        start(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )

    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return openAppDetails(context)
        return start(
            context,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        ) || openAppDetails(context)
    }

    fun openFullScreenSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return openAppDetails(context)
        return start(
            context,
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        ) || openAppDetails(context)
    }

    fun openBatterySettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetails(context)

    fun requestIgnoreBatteryOptimization(context: Context): Boolean =
        start(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        ) || openBatterySettings(context)

    fun openDndSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) ||
            openNotificationSettings(context)

    private fun component(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
