package com.hikaru.parkingalert.data

/** How an incoming matching SMS should be announced. */
enum class AlertMode {
    GENTLE_THEN_ALARM,
    ALARM_ONLY,
    NOTIFY_ONLY;

    companion object {
        fun fromRaw(raw: String?): AlertMode =
            entries.firstOrNull { it.name == raw } ?: GENTLE_THEN_ALARM
    }
}

enum class AlertStage {
    GENTLE,
    VIBRATE,
    ALARM,
}

enum class AlertStatus {
    PENDING,
    ACKNOWLEDGED,
    SNOOZED,
    MISSED,
    CANCELLED,
}

/** The one alert that is currently waiting for the user. */
data class PendingAlert(
    val id: Long,
    val sender: String,
    val body: String,
    val keywords: List<String>,
    val receivedAt: Long,
    val escalationAt: Long,
    val stage: AlertStage,
)

/** A compact, on-device history entry. */
data class AlertRecord(
    val id: Long,
    val sender: String,
    val body: String,
    val keywords: List<String>,
    val receivedAt: Long,
    val status: AlertStatus,
)
