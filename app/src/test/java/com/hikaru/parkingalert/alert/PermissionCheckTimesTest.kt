package com.hikaru.parkingalert.alert

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionCheckTimesTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, 23, hour, minute, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

    private fun next(now: Long): Long = PermissionCheckTimes.nextTriggerAt(now, zone)

    @Test
    fun `before nine schedules same day nine`() {
        assertEquals(at(9), next(at(8, 59)))
    }

    @Test
    fun `at nine schedules same day eighteen`() {
        assertEquals(at(18), next(at(9)))
    }

    @Test
    fun `between nine and eighteen schedules eighteen`() {
        assertEquals(at(18), next(at(12, 30)))
    }

    @Test
    fun `after eighteen schedules next day nine`() {
        val nextDayAtNine = ZonedDateTime.of(2026, 11, 24, 9, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(nextDayAtNine, next(at(18)))
        assertEquals(nextDayAtNine, next(at(23, 59)))
    }
}
