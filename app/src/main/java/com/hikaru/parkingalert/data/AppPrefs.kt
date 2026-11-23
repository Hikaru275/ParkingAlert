package com.hikaru.parkingalert.data

import android.annotation.SuppressLint
import android.content.Context
import com.hikaru.parkingalert.sms.KeywordMatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, dependency-free persistence layer. Everything stays in the app's private
 * SharedPreferences file and is excluded from cloud backup by the XML rules.
 */
object AppPrefs {

    const val PREFS_NAME = "parking_alert_prefs"

    val DEFAULT_KEYWORDS = listOf("交警", "触发", "违停", "汽车")

    const val GENTLE_DELAY_MS = 60_000L
    const val VIBRATE_DELAY_MS = 60_000L
    const val VIBRATION_SECONDS = 12
    const val DEFAULT_RING_SECONDS = 60
    const val SNOOZE_MINUTES = 5
    const val MAX_HISTORY = 50

    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_ALERT_MODE = "alert_mode"
    private const val KEY_RING = "ring_seconds"
    private const val KEY_STRICT = "strict_mode"
    private const val KEY_MARKETING = "ignore_marketing"
    private const val KEY_RAMP = "volume_ramp"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_DND = "bypass_dnd"
    private const val KEY_GUARDIAN = "guardian_enabled"
    private const val KEY_AUTOSTART = "autostart_confirmed"
    private const val KEY_SAVE_HISTORY = "save_history"
    private const val KEY_HISTORY = "history"
    private const val KEY_PENDING = "pending_alert"
    private const val KEY_LAST_HASH = "last_sms_hash"
    private const val KEY_LAST_AT = "last_sms_at"

    private const val DUPLICATE_WINDOW_MS = 120_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------
    // Keywords and matching
    // ---------------------------------------------------------------------

    fun keywords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_KEYWORDS, null) ?: return DEFAULT_KEYWORDS.toList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val keyword = array.optString(index).trim()
                    if (keyword.isNotEmpty()) add(keyword)
                }
            }.distinctBy { KeywordMatcher.normalize(it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: DEFAULT_KEYWORDS.toList()
    }

    fun setKeywords(context: Context, values: List<String>) {
        val cleaned = values
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { KeywordMatcher.normalize(it) }
            .take(30)
        prefs(context).edit().putString(KEY_KEYWORDS, JSONArray(cleaned).toString()).apply()
    }

    fun strictMode(context: Context): Boolean = prefs(context).getBoolean(KEY_STRICT, false)

    fun setStrictMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT, enabled).apply()
    }

    fun ignoreMarketing(context: Context): Boolean = prefs(context).getBoolean(KEY_MARKETING, true)

    fun setIgnoreMarketing(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MARKETING, enabled).apply()
    }

    // ---------------------------------------------------------------------
    // Alert style
    // ---------------------------------------------------------------------

    fun alertMode(context: Context): AlertMode =
        AlertMode.fromRaw(prefs(context).getString(KEY_ALERT_MODE, null))

    fun setAlertMode(context: Context, mode: AlertMode) {
        prefs(context).edit().putString(KEY_ALERT_MODE, mode.name).apply()
    }

    fun ringSeconds(context: Context): Int =
        prefs(context).getInt(KEY_RING, DEFAULT_RING_SECONDS).coerceIn(15, 120)

    fun setRingSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_RING, seconds.coerceIn(15, 120)).apply()
    }

    fun volumeRamp(context: Context): Boolean = prefs(context).getBoolean(KEY_RAMP, true)

    fun setVolumeRamp(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAMP, enabled).apply()
    }

    fun vibrate(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATE, true)

    fun setVibrate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, enabled).apply()
    }

    fun bypassDnd(context: Context): Boolean = prefs(context).getBoolean(KEY_DND, false)

    fun setBypassDnd(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DND, enabled).apply()
    }

    // ---------------------------------------------------------------------
    // Background behaviour
    // ---------------------------------------------------------------------

    fun guardianEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GUARDIAN, true)

    fun setGuardianEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUARDIAN, enabled).apply()
    }

    /**
     * Android has no public API to read the OEM autostart switch, so the user confirms
     * it once after returning from the vendor settings page.
     */
    fun autostartConfirmed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutostartConfirmed(context: Context, confirmed: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, confirmed).apply()
    }

    fun saveHistory(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_HISTORY, true)

    fun setSaveHistory(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_HISTORY, enabled).apply()
    }

    // ---------------------------------------------------------------------
    // Pending alert
    // ---------------------------------------------------------------------

    fun pending(context: Context): PendingAlert? {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return null
        return runCatching { pendingFromJson(JSONObject(raw)) }.getOrNull()
    }

    @SuppressLint("ApplySharedPref")
    fun setPending(context: Context, pending: PendingAlert) {
        // commit: the pending alert must survive an immediate process kill.
        prefs(context).edit().putString(KEY_PENDING, pendingToJson(pending).toString()).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).commit()
    }

    // ---------------------------------------------------------------------
    // Duplicate suppression
    // ---------------------------------------------------------------------

    /**
     * Returns true when an identical sender+body arrived within the last two minutes.
     * This prevents a multipart SMS or a carrier resend from ringing twice.
     */
    fun shouldSkipDuplicate(context: Context, sender: String, body: String): Boolean {
        val hash = KeywordMatcher.normalize("$sender|$body").hashCode().toLong()
        val now = System.currentTimeMillis()
        val prefs = prefs(context)
        val previousHash = prefs.getLong(KEY_LAST_HASH, 0L)
        val previousAt = prefs.getLong(KEY_LAST_AT, 0L)
        if (previousHash == hash && now - previousAt in 0..DUPLICATE_WINDOW_MS) {
            return true
        }
        prefs.edit()
            .putLong(KEY_LAST_HASH, hash)
            .putLong(KEY_LAST_AT, now)
            .apply()
        return false
    }

    // ---------------------------------------------------------------------
    // History
    // ---------------------------------------------------------------------

    fun history(context: Context): List<AlertRecord> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    recordFromJson(json)?.let(::add)
                }
            }.sortedByDescending { it.receivedAt }
        }.getOrDefault(emptyList())
    }

    fun addRecord(context: Context, record: AlertRecord) {
        val records = history(context).toMutableList()
        records.removeAll { it.id == record.id }
        records.add(0, record)
        while (records.size > MAX_HISTORY) {
            records.removeAt(records.lastIndex)
        }
        writeHistory(context, records)
    }

    fun updateRecordStatus(context: Context, id: Long, status: AlertStatus) {
        val records = history(context)
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = records.toMutableList()
        updated[index] = updated[index].copy(status = status)
        writeHistory(context, updated)
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }

    private fun writeHistory(context: Context, records: List<AlertRecord>) {
        val array = JSONArray()
        records.forEach { array.put(recordToJson(it)) }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    // ---------------------------------------------------------------------
    // JSON mapping
    // ---------------------------------------------------------------------

    private fun pendingToJson(pending: PendingAlert): JSONObject = JSONObject()
        .put("id", pending.id)
        .put("sender", pending.sender)
        .put("body", pending.body)
        .put("keywords", JSONArray(pending.keywords))
        .put("receivedAt", pending.receivedAt)
        .put("escalationAt", pending.escalationAt)
        .put("stage", pending.stage.name)

    private fun pendingFromJson(json: JSONObject): PendingAlert? {
        val id = json.optLong("id", 0L)
        if (id == 0L) return null
        val stage = runCatching {
            AlertStage.valueOf(json.optString("stage", AlertStage.GENTLE.name))
        }.getOrDefault(AlertStage.GENTLE)
        return PendingAlert(
            id = id,
            sender = json.optString("sender"),
            body = json.optString("body"),
            keywords = stringList(json.optJSONArray("keywords")),
            receivedAt = json.optLong("receivedAt", id),
            escalationAt = json.optLong("escalationAt", 0L),
            stage = stage,
        )
    }

    private fun recordToJson(record: AlertRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("sender", record.sender)
        .put("body", record.body)
        .put("keywords", JSONArray(record.keywords))
        .put("receivedAt", record.receivedAt)
        .put("status", record.status.name)

    private fun recordFromJson(json: JSONObject): AlertRecord? {
        val id = json.optLong("id", 0L)
        if (id == 0L) return null
        val status = runCatching {
            AlertStatus.valueOf(json.optString("status", AlertStatus.PENDING.name))
        }.getOrDefault(AlertStatus.PENDING)
        return AlertRecord(
            id = id,
            sender = json.optString("sender"),
            body = json.optString("body"),
            keywords = stringList(json.optJSONArray("keywords")),
            receivedAt = json.optLong("receivedAt", id),
            status = status,
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
