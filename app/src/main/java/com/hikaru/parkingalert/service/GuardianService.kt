package com.hikaru.parkingalert.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hikaru.parkingalert.util.NotificationHelper

/**
 * Optional low-priority foreground service. The manifest SMS receiver is the actual
 * delivery mechanism; this service simply makes the process less likely to be frozen
 * by aggressive OEM battery managers.
 */
class GuardianService : Service() {

    companion object {
        private const val ACTION_START = "com.hikaru.parkingalert.service.GUARDIAN_START"
        private const val ACTION_STOP = "com.hikaru.parkingalert.service.GUARDIAN_STOP"

        fun start(context: Context): Boolean {
            val intent = Intent(context, GuardianService::class.java).setAction(ACTION_START)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardianService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.ID_GUARDIAN,
                NotificationHelper.buildGuardian(this),
                type,
            )
            START_STICKY
        } catch (_: Exception) {
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
