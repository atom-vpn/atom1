package com.atomvpn.mobile.engine.atom

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Isolated one-way compatibility boundary for data created by earlier installs.
 *
 * Historical storage identifiers stay here so active engine APIs use only
 * Atom-owned names. Keep these mappings until a tested migration replaces them.
 */
internal object LegacyInstallMigration {
    private const val LEGACY_ENGINE_PREFERENCES = "uac_pow_engine_v1"
    private const val LEGACY_DNS_PREFERENCES = "uac_pow_dns_warm"

    private const val LEGACY_IDENTITY_FILE = "uac-pow.toml"
    private const val LEGACY_ENGINE_DIRECTORY = "uac-pow"
    private const val LEGACY_MASQUE_CACHE = "uac-pow-masque-cache.json"
    private const val LEGACY_LAST_CONNECTION = "uac-pow-lastconn.toml"
    private const val LEGACY_GOOL_LAST_CONNECTION = "uac-pow-gool-lastconn.toml"
    private const val LEGACY_CLEAN_H2_CACHE = "uac-pow-clean-h2.json"

    private fun migrateFile(context: Context, legacyName: String, currentName: String): File {
        val target = File(context.filesDir, currentName)
        val legacy = File(context.filesDir, legacyName)
        if (!target.exists() && legacy.isFile) runCatching { legacy.copyTo(target, overwrite = false) }
        return target
    }

    fun identityPath(context: Context): File =
        migrateFile(context, LEGACY_IDENTITY_FILE, "atom-engine.toml")

    fun hevConfigPath(context: Context): File {
        val targetDir = File(context.filesDir, "atom-engine")
        val target = File(targetDir, "hev.yml")
        val legacy = File(File(context.filesDir, LEGACY_ENGINE_DIRECTORY), "hev.yml")
        if (!target.exists() && legacy.isFile) {
            runCatching {
                targetDir.mkdirs()
                legacy.copyTo(target, overwrite = false)
            }
        }
        return target
    }

    fun masqueCachePath(context: Context): File =
        migrateFile(context, LEGACY_MASQUE_CACHE, "atom-engine-masque-cache.json")

    fun lastconnPath(context: Context): File =
        migrateFile(context, LEGACY_LAST_CONNECTION, "atom-engine-lastconn.toml")

    fun goolLastconnPath(context: Context): File =
        migrateFile(context, LEGACY_GOOL_LAST_CONNECTION, "atom-engine-gool-lastconn.toml")

    fun cleanH2File(context: Context): File =
        migrateFile(context, LEGACY_CLEAN_H2_CACHE, "atom-engine-clean-h2.json")

    fun clearObsoleteLastConnMemory(context: Context): Int {
        val files = listOf(
            File(context.filesDir, "atom-engine-lastconn.toml"),
            File(context.filesDir, LEGACY_LAST_CONNECTION),
            File(context.filesDir, "atom-engine-gool-lastconn.toml"),
            File(context.filesDir, LEGACY_GOOL_LAST_CONNECTION),
        )
        return files.count { file -> runCatching { file.isFile && file.delete() }.getOrDefault(false) }
    }

    fun migrateEnginePreferences(context: Context) {
        val app = context.applicationContext
        val legacy = app.getSharedPreferences(LEGACY_ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        val current = app.getSharedPreferences("atom_engine_v1", Context.MODE_PRIVATE)
        val oldValues = legacy.all
        if (oldValues.isEmpty()) return
        val editor = current.edit()
        for ((key, value) in oldValues) {
            if (current.contains(key)) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
            }
        }
        editor.commit()
    }

    fun dnsWarmPreferences(context: Context, currentName: String, key: String): SharedPreferences {
        val app = context.applicationContext
        val legacy = app.getSharedPreferences(LEGACY_DNS_PREFERENCES, Context.MODE_PRIVATE)
        val current = app.getSharedPreferences(currentName, Context.MODE_PRIVATE)
        if (!current.contains(key) && legacy.contains(key)) {
            current.edit().putString(key, legacy.getString(key, null)).commit()
        }
        return current
    }
}
