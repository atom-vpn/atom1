package com.atomvpn.mobile

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * اطلاعات سازنده و اعلان‌ها که از یک فایل JSON آنلاین (remote-config.json) خوانده می‌شود.
 * مقدارهای پیش‌فرض همان مقدارهای داخل فایل هستند، پس بدون اینترنت هم صفحه خالی نمی‌ماند.
 */
data class AtomRemoteConfig(
    val appName: String = "Atom VPN",
    val about: String = "Atom VPN - Secure Connection",
    val developerName: String = "Team ATom",
    val developerRole: String = "Atom VPN Development Team",
    val githubUrl: String = "https://github.com/atom-vpn/atom1",
    val telegramUrl: String = "https://t.me/AtomCNT",
    val supportEmail: String = "",
    val announcement: String = "",
    val latestVersion: String = "",
    val downloadUrl: String = "",
) {
    companion object {
        /** فقط به همین میزبان‌ها لینک داده می‌شود، حتی اگر فایل JSON دستکاری شود. */
        private val ALLOWED_HOSTS = setOf("t.me", "telegram.me", "github.com", "www.github.com")

        fun parse(json: String): AtomRemoteConfig? = try {
            val o = JSONObject(json)
            val d = AtomRemoteConfig()
            AtomRemoteConfig(
                appName = o.text("app_name", d.appName, 40),
                about = o.text("about", d.about, 200),
                developerName = o.text("developer_name", d.developerName, 60),
                developerRole = o.text("developer_role", d.developerRole, 80),
                githubUrl = o.text("github_url", d.githubUrl, 300),
                telegramUrl = o.text("telegram_url", d.telegramUrl, 300),
                supportEmail = o.text("support_email", d.supportEmail, 100),
                announcement = o.text("announcement", d.announcement, 300),
                latestVersion = o.text("latest_version", d.latestVersion, 24),
                downloadUrl = o.text("download_url", d.downloadUrl, 300),
            )
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.text(key: String, fallback: String, max: Int): String {
            if (!has(key) || isNull(key)) return fallback
            return optString(key, fallback)
                .filter { !it.isISOControl() || it == '\n' }
                .trim()
                .take(max)
        }

        /** فقط https و فقط میزبان‌های مجاز؛ در غیر این صورت null. */
        fun safeUrl(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 300) return null
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "https") return null
            if (uri.userInfo != null) return null
            val host = uri.host?.lowercase() ?: return null
            return if (host in ALLOWED_HOSTS) s else null
        }

        fun isEmail(value: String): Boolean =
            value.length in 5..100 && android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()

        fun isNewer(latest: String, current: String): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
            val a = parts(latest)
            val b = parts(current)
            if (a.isEmpty()) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

class AtomRemoteConfigStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableConfig = MutableStateFlow(
        prefs.getString(KEY_JSON, null)?.let { AtomRemoteConfig.parse(it) } ?: AtomRemoteConfig()
    )
    val config: StateFlow<AtomRemoteConfig> = mutableConfig.asStateFlow()

    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()
    private val mutex = Mutex()

    /** اگر آخرین دریافت کمتر از یک دقیقه پیش بوده دوباره نمی‌گیرد، مگر force = true. */
    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_FETCHED_AT, 0L) < MIN_INTERVAL_MS) return
        if (!mutex.tryLock()) return
        try {
            mutableRefreshing.value = true
            val body = withContext(Dispatchers.IO) { fetch() }
            val parsed = body?.let { AtomRemoteConfig.parse(it) }
            if (body != null && parsed != null) {
                prefs.edit().putString(KEY_JSON, body).putLong(KEY_FETCHED_AT, now).apply()
                mutableConfig.value = parsed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // بدون اینترنت یا خطا: همان مقدار ذخیره‌شده می‌ماند
        } finally {
            mutableRefreshing.value = false
            mutex.unlock()
        }
    }

    private fun fetch(): String? {
        if (!REMOTE_CONFIG_URL.startsWith("https://")) return null
        val conn = URL(REMOTE_CONFIG_URL).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readLimited(MAX_BYTES) }?.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun InputStream.readLimited(max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * آدرس فایل remote-config.json را اینجا بگذار. باید https باشد.
         * اگر شاخه‌ی ریپو main نیست یا فایل جای دیگری است، همین خط را عوض کن.
         */
        const val REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/atom-vpn/atom1/main/remote-config.json"

        private const val PREFS = "atom_remote_config"
        private const val KEY_JSON = "json"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val MIN_INTERVAL_MS = 60_000L
        private const val MAX_BYTES = 32 * 1024

        @Volatile private var instance: AtomRemoteConfigStore? = null

        fun get(context: Context): AtomRemoteConfigStore = instance ?: synchronized(this) {
            instance ?: AtomRemoteConfigStore(context.applicationContext).also { instance = it }
        }
    }
}
