package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    @Test
    fun `required SMS grant unlocks shell before model preparation`() {
        val repository = mockk<SmsRepository>()
        every { repository.hasPermissions() } returns false
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(fake.preferences, hasSmsPermissions = true)
        val configuration = mockk<ProcessingConfigurationRepository>()
        every { configuration.confirmedPrimaryCurrency() } returns null
        every { configuration.confirmPrimaryCurrency("INR") } returns Unit
        val viewModel = OnboardingViewModel(repository, store, configuration)

        viewModel.setStep(OnboardingStep.PRIMARY_CURRENCY)
        viewModel.confirmPrimaryCurrency()
        viewModel.onPermissionResult(granted = true)

        assertEquals(OnboardingStep.COMPLETED, viewModel.state.value.step)
        assertEquals(SetupImportStatus.NOT_STARTED, store.state.value.status)
        assertEquals(true, fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED])
    }

    @Test
    fun `selectPrimaryCurrency normalizes supported codes and rejects unknown ones`() {
        val repository = mockk<SmsRepository>()
        every { repository.hasPermissions() } returns false
        val store = SetupImportStore(FakeSharedPreferences().preferences, hasSmsPermissions = false)
        val configuration = mockk<ProcessingConfigurationRepository>(relaxed = true)
        every { configuration.confirmedPrimaryCurrency() } returns null
        val viewModel = OnboardingViewModel(repository, store, configuration)

        viewModel.selectPrimaryCurrency("usd")
        assertEquals("USD", viewModel.state.value.selectedPrimaryCurrency)

        assertFailsWith<IllegalArgumentException> {
            viewModel.selectPrimaryCurrency("ZZZ")
        }

        viewModel.confirmPrimaryCurrency()
        verify(exactly = 1) { configuration.confirmPrimaryCurrency("USD") }
        assertEquals(OnboardingStep.PERMISSIONS, viewModel.state.value.step)
        assertTrue(viewModel.state.value.primaryCurrencyConfirmed)
    }

    @Test
    fun `permission denial increments deniedCount and later checkPermissions unlocks shell`() {
        var granted = false
        val repository = mockk<SmsRepository>()
        every { repository.hasPermissions() } answers { granted }
        val store = SetupImportStore(FakeSharedPreferences().preferences, hasSmsPermissions = false)
        val configuration = mockk<ProcessingConfigurationRepository>(relaxed = true)
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val viewModel = OnboardingViewModel(repository, store, configuration)

        viewModel.setStep(OnboardingStep.PERMISSIONS)
        viewModel.onPermissionResult(granted = false)
        assertEquals(1, viewModel.state.value.deniedCount)
        assertEquals(OnboardingStep.PERMISSIONS, viewModel.state.value.step)

        granted = true
        viewModel.checkPermissions()
        assertEquals(OnboardingStep.COMPLETED, viewModel.state.value.step)
        assertTrue(viewModel.state.value.hasPermissions)
    }

    @Test
    fun `pending local erase recovery blocks shell completion`() {
        val repository = mockk<SmsRepository>()
        every { repository.hasPermissions() } returns true
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(fake.preferences, hasSmsPermissions = true)
        store.beginLocalFinancialErase(nextRunGeneration = 1L)
        val configuration = mockk<ProcessingConfigurationRepository>()
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val viewModel = OnboardingViewModel(repository, store, configuration)

        viewModel.setStep(OnboardingStep.PERMISSIONS)

        assertEquals(OnboardingStep.PERMISSIONS, viewModel.state.value.step)
        assertFalse(fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED] as Boolean)
        assertTrue(store.isLocalFinancialErasePending())
    }

    @Test
    fun `background work notice reflects notification permission availability`() {
        assertEquals(
            "You can switch apps and track progress in notifications.",
            backgroundWorkNoticeText(hasNotificationPermission = true)
        )
        assertEquals(
            "You can switch apps and return to Pocket Financer to check progress.",
            backgroundWorkNoticeText(hasNotificationPermission = false)
        )
    }
}
