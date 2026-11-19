package com.atomvpn.mobile

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AtomTelemetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AtomTelemetry.isEnabled(applicationContext)) return@withContext Result.success()

        val baseUrl = BuildConfig.TELEMETRY_BASE_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) return@withContext Result.success()

        val events = AtomTelemetry.pendingEvents(applicationContext)
        if (events.length() == 0) return@withContext Result.success()

        val sentIds = buildSet {
            for (index in 0 until events.length()) {
                events.optJSONObject(index)?.optString("event_id")
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        try {
            val connection = (URL("$baseUrl/v1/events/batch").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Atom-Client", "android")
            }

            try {
                val payload = JSONObject().put("events", JSONArray(events.toString()))
                connection.outputStream.use {
                    it.write(payload.toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                when {
                    status in 200..299 -> {
                        AtomTelemetry.removeSentEvents(applicationContext, sentIds)
                        Result.success()
                    }
                    status == 429 || status >= 500 -> Result.retry()
                    else -> Result.failure()
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
