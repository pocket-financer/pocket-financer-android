package com.pocketfinancer.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProcessingConfigurationTest {

    private lateinit var repository: ProcessingConfigurationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = ProcessingConfigurationRepository(context)
        repository.clear()
    }

    @Test
    fun `primary currency is unconfirmed by default and persists normalized code after confirm`() {
        assertNull(repository.confirmedPrimaryCurrency())

        repository.confirmPrimaryCurrency("inr")
        assertEquals("INR", repository.confirmedPrimaryCurrency())

        repository.confirmPrimaryCurrency("jpy")
        assertEquals("JPY", repository.confirmedPrimaryCurrency())

        repository.clear()
        assertNull(repository.confirmedPrimaryCurrency())
    }

    @Test
    fun `confirmPrimaryCurrency rejects unsupported codes`() {
        assertFailsWith<IllegalArgumentException> {
            repository.confirmPrimaryCurrency("XYZ")
        }
        assertNull(repository.confirmedPrimaryCurrency())
    }

    @Test
    fun `enabledProfiles attaches india profile only for INR`() {
        assertEquals(listOf("core-en", "india"), repository.enabledProfiles("INR"))
        assertEquals(listOf("core-en", "india"), repository.enabledProfiles("inr"))
        assertEquals(listOf("core-en"), repository.enabledProfiles("USD"))
        assertEquals(listOf("core-en"), repository.enabledProfiles("JPY"))
    }

    @Test
    fun `CurrencyScaleRegistry defines exact minor unit scales across supported currencies`() {
        assertEquals(2, CurrencyScaleRegistry.scale("INR"))
        assertEquals(2, CurrencyScaleRegistry.scale("usd"))
        assertEquals(0, CurrencyScaleRegistry.scale("JPY"))
        assertNull(CurrencyScaleRegistry.scale("UNKNOWN"))
        assertEquals(
            ProcessingConfigurationRepository.SUPPORTED.sorted(),
            CurrencyScaleRegistry.supportedCodes
        )
        assertTrue(CurrencyScaleRegistry.supportedCodes.containsAll(listOf("INR", "USD", "JPY")))
    }
}
