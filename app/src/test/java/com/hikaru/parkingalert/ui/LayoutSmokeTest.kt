package com.hikaru.parkingalert.ui

import android.view.LayoutInflater
import android.view.View
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.hikaru.parkingalert.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Catches resource/theme inflation failures without needing a physical device.
 * These tests do not exercise the SMS broadcast or audio hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LayoutSmokeTest {

    @Test
    fun mainLayoutInflatesAndModeSelectorWorks() {
        val app = RuntimeEnvironment.getApplication()
        app.setTheme(R.style.Theme_ParkingAlert)
        val view = LayoutInflater.from(app).inflate(R.layout.activity_main, null, false)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.alertModeGroup)
        val gentle = view.findViewById<MaterialButton>(R.id.modeGentle)

        assertNotNull(view.findViewById<View>(R.id.toolbar))
        assertNotNull(view.findViewById<View>(R.id.statusTitle))
        assertNotNull(view.findViewById<View>(R.id.keywordChipGroup))
        assertNotNull(view.findViewById<View>(R.id.historyContainer))

        group.check(R.id.modeGentle)

        assertTrue(gentle.isChecked)
    }

    @Test
    fun alertLayoutInflatesWithAlertTheme() {
        val app = RuntimeEnvironment.getApplication()
        app.setTheme(R.style.Theme_ParkingAlert_Alert)

        val view = LayoutInflater.from(app).inflate(R.layout.activity_alert, null, false)

        assertNotNull(view.findViewById<View>(R.id.alertBody))
        assertNotNull(view.findViewById<View>(R.id.ackButton))
        assertNotNull(view.findViewById<View>(R.id.snoozeButton))
        assertNotNull(view.findViewById<View>(R.id.silenceButton))
    }
}
