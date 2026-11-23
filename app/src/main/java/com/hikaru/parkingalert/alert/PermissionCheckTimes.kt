package com.hikaru.parkingalert.alert

import java.time.Instant
import java.time.ZoneId

/** Pure time calculation for the twice-daily permission audit. */
object PermissionCheckTimes {

    const val MORNING_HOUR = 9
    const val EVENING_HOUR = 18

    fun nextTriggerAt(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val candidates = listOf(
            today.atTime(MORNING_HOUR, 0).atZone(zone),
            today.atTime(EVENING_HOUR, 0).atZone(zone),
        )
        return candidates
            .firstOrNull { it.toInstant().toEpochMilli() > now }
            ?.toInstant()
            ?.toEpochMilli()
            ?: today.plusDays(1)
                .atTime(MORNING_HOUR, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
    }
}
