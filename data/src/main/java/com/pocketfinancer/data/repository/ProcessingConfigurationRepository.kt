package com.pocketfinancer.data.repository

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessingConfigurationRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun confirmedPrimaryCurrency(): String? {
        if (!preferences.getBoolean(KEY_CONFIRMED, false)) return null
        return preferences.getString(KEY_CURRENCY, null)?.uppercase()?.takeIf(SUPPORTED::contains)
    }

    fun confirmPrimaryCurrency(currency: String) {
        val normalized = currency.uppercase()
        require(normalized in SUPPORTED) { "Unsupported primary currency" }
        preferences.edit(commit = true) {
            putString(KEY_CURRENCY, normalized)
            putBoolean(KEY_CONFIRMED, true)
        }
    }

    fun clear() {
        preferences.edit(commit = true) { clear() }
    }

    fun enabledProfiles(currency: String): List<String> =
        if (currency.uppercase() == "INR") listOf("core-en", "india") else listOf("core-en")

    companion object {
        const val KEY_CURRENCY = "sms_processing_primary_currency"
        const val KEY_CONFIRMED = "sms_processing_primary_currency_confirmed"
        private const val PREFERENCES = "sms_processing_configuration"
        val SUPPORTED = setOf("AED", "AUD", "CAD", "CHF", "EUR", "GBP", "INR", "JPY", "SGD", "USD")
    }
}
