package com.hikaru.parkingalert.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.hikaru.parkingalert.alert.AlertCoordinator
import com.hikaru.parkingalert.data.AppPrefs
import com.hikaru.parkingalert.util.PermissionUtils

/**
 * Receives the system SMS broadcast even when the app is not running. This is the
 * "always listening" part of the app; no long-lived polling process is required.
 */
class SmsReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!PermissionUtils.hasSms(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val body = buildString {
            messages.forEach { message ->
                message?.messageBody?.let(::append)
            }
        }
        if (body.isBlank()) return

        val sender = messages.firstOrNull()?.let { message ->
            message.displayOriginatingAddress ?: message.originatingAddress
        }.orEmpty()

        val match = KeywordMatcher.match(
            body = body,
            keywords = AppPrefs.keywords(context),
            strictMode = AppPrefs.strictMode(context),
            ignoreMarketing = AppPrefs.ignoreMarketing(context),
        )
        if (!match.shouldAlert) return

        val receivedAt = messages.firstOrNull()?.timestampMillis
            ?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        AlertCoordinator.onSmsMatched(
            context = context,
            sender = sender,
            body = body,
            matchedKeywords = match.matchedKeywords,
            receivedAt = receivedAt,
        )
    }
}
