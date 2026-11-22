package com.hikaru.parkingalert

import android.app.Application
import com.hikaru.parkingalert.util.NotificationHelper

class ParkingAlertApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }
}
