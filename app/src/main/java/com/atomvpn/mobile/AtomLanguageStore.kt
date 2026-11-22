package com.atomvpn.mobile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted in-app language preference. */
class AtomLanguageStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableLanguage = MutableStateFlow(normalize(prefs.getString(KEY_LANGUAGE, FA)))
    val language: StateFlow<String> = mutableLanguage.asStateFlow()

    fun setLanguage(value: String) {
        val clean = normalize(value)
        prefs.edit().putString(KEY_LANGUAGE, clean).apply()
        mutableLanguage.value = clean
    }

    companion object {
        const val FA = "fa"
        const val EN = "en"
        private const val PREFS = "atom_ui_prefs"
        private const val KEY_LANGUAGE = "app_language"

        private fun normalize(value: String?): String = if (value == EN) EN else FA

        @Volatile private var instance: AtomLanguageStore? = null

        fun get(context: Context): AtomLanguageStore = instance ?: synchronized(this) {
            instance ?: AtomLanguageStore(context.applicationContext).also { instance = it }
        }
    }
}
