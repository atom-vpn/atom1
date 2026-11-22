package com.atomvpn.mobile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** ذخیره‌ی تم برنامه: خودکار (پیرو گوشی) / تیره / روشن. */
class AtomAppearanceStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(normalize(prefs.getString(KEY_MODE, AUTO)))
    val mode: StateFlow<String> = mutableMode.asStateFlow()

    fun setMode(value: String) {
        val clean = normalize(value)
        prefs.edit().putString(KEY_MODE, clean).apply()
        mutableMode.value = clean
    }

    companion object {
        const val AUTO = "auto"
        const val DARK = "dark"
        const val LIGHT = "light"
        private const val PREFS = "atom_ui_prefs"
        private const val KEY_MODE = "theme_mode"

        private fun normalize(value: String?): String = when (value) {
            DARK -> DARK
            LIGHT -> LIGHT
            else -> AUTO
        }

        @Volatile private var instance: AtomAppearanceStore? = null

        fun get(context: Context): AtomAppearanceStore = instance ?: synchronized(this) {
            instance ?: AtomAppearanceStore(context.applicationContext).also { instance = it }
        }
    }
}
