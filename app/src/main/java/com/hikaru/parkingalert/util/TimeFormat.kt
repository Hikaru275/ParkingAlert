package com.hikaru.parkingalert.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeFormat {

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val shortFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)
    private val fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun clock(timestamp: Long): String = format(timestamp, clockFormatter)

    fun short(timestamp: Long): String = format(timestamp, shortFormatter)

    fun full(timestamp: Long): String = format(timestamp, fullFormatter)

    private fun format(timestamp: Long, formatter: DateTimeFormatter): String =
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)
}
