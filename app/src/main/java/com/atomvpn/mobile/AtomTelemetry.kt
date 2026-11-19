package com.atomvpn.mobile

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt-in, anonymous product statistics.
 *
 * Only a random installation UUID, event type, app version and event timestamp
 * are sent. No IP address, account data, device identifiers or VPN traffic data
 * are added to the event payload.
 */
object AtomTelemetry {
    private const val PREFS = "atom_telemetry"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_INSTALL_QUEUED = "install_queued"
    private const val KEY_LAST_ACTIVE_DATE = "last_active_date"
    private const val KEY_QUEUE = "pending_events"
    private const val MAX_QUEUE_SIZE = 500
    private const val UNIQUE_WORK_NAME = "atom-telemetry-upload"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            recordAppOpen(context)
        } else {
            prefs.edit()
                .remove(KEY_QUEUE)
                .remove(KEY_INSTALL_QUEUED)
                .remove(KEY_LAST_ACTIVE_DATE)
                .apply()
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    /** Called when the app enters the foreground. At most one active event per UTC day. */
    fun recordAppOpen(context: Context) {
        if (!isEnabled(context) || BuildConfig.TELEMETRY_BASE_URL.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installId = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }
        val now = Instant.now()
        val queue = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]")) }
            .getOrDefault(JSONArray())

        var installQueued = prefs.getBoolean(KEY_INSTALL_QUEUED, false)
        if (!installQueued) {
            queue.put(newEvent(installId, "install", now))
            installQueued = true
        }

        val todayUtc = LocalDate.now(ZoneOffset.UTC).toString()
        var lastActiveDate = prefs.getString(KEY_LAST_ACTIVE_DATE, null)
        if (lastActiveDate != todayUtc) {
            queue.put(newEvent(installId, "active", now))
            lastActiveDate = todayUtc
        }

        while (queue.length() > MAX_QUEUE_SIZE) queue.remove(0)
        // Persist queue and deduplication markers atomically to avoid losing the first event on process death.
        prefs.edit()
            .putBoolean(KEY_INSTALL_QUEUED, installQueued)
            .putString(KEY_LAST_ACTIVE_DATE, lastActiveDate)
            .putString(KEY_QUEUE, queue.toString())
            .apply()
        scheduleUpload(context)
    }

    internal fun pendingEvents(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]")) }
            .getOrDefault(JSONArray())
    }

    internal fun removeSentEvents(context: Context, sentIds: Set<String>) {
        if (sentIds.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = pendingEvents(context)
        val remaining = JSONArray()
        for (index in 0 until current.length()) {
            val event = current.optJSONObject(index) ?: continue
            if (event.optString("event_id") !in sentIds) remaining.put(event)
        }
        prefs.edit().putString(KEY_QUEUE, remaining.toString()).apply()
    }

    private fun newEvent(installId: String, eventType: String, occurredAt: Instant) =
        JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("install_id", installId)
            .put("event_type", eventType)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("occurred_at", occurredAt.toString())

    private fun scheduleUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<AtomTelemetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
