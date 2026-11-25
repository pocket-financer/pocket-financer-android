package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import io.mockk.every
import io.mockk.mockk
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
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        val configuration = mockk<ProcessingConfigurationRepository>()
        every { configuration.confirmedPrimaryCurrency() } returns null
        every { configuration.confirmPrimaryCurrency("INR") } returns Unit
        val viewModel = OnboardingViewModel(repository, store, configuration)

        viewModel.setStep(OnboardingStep.PRIMARY_CURRENCY)
        viewModel.confirmPrimaryCurrency()
        viewModel.onPermissionResult(granted = true)

        assertEquals(OnboardingStep.COMPLETED, viewModel.state.value.step)
        assertEquals(SetupImportStatus.NOT_STARTED, store.state.value.status)
        assertEquals(
            true,
            fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED]
        )
    }

    @Test
    fun `pending local erase recovery blocks shell completion`() {
        val repository = mockk<SmsRepository>()
        every { repository.hasPermissions() } returns true
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.beginLocalFinancialErase(nextRunGeneration = 1L)
        val configuration = mockk<ProcessingConfigurationRepository>()
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        val viewModel = OnboardingViewModel(repository, store, configuration)

        viewModel.setStep(OnboardingStep.PERMISSIONS)

        assertEquals(OnboardingStep.PERMISSIONS, viewModel.state.value.step)
        assertFalse(
            fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED] as Boolean
        )
        assertTrue(store.isLocalFinancialErasePending())
    }
}
