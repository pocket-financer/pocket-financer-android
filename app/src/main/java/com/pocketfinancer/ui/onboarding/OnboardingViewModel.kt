package com.pocketfinancer.ui.onboarding

import androidx.lifecycle.ViewModel
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pre-shell flow intentionally owns only the introduction and required SMS
 * permissions. Model preparation and history import live in the durable Home
 * setup flow.
 */
enum class OnboardingStep {
    WELCOME,
    PRIMARY_CURRENCY,
    PERMISSIONS,
    DOWNLOAD_SLM,
    SYNCING,
    COMPLETED
}

data class ExtractedTxPreview(
    val amount: Double,
    val merchant: String,
    val type: String
)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val hasPermissions: Boolean = false,
    val deniedCount: Int = 0,
    val selectedPrimaryCurrency: String = "INR",
    val primaryCurrencyConfirmed: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val smsRepository: SmsRepository,
    private val setupImportStore: SetupImportStore,
    private val processingConfiguration: ProcessingConfigurationRepository
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        processingConfiguration.confirmedPrimaryCurrency()?.let { currency ->
            _state.value = _state.value.copy(
                selectedPrimaryCurrency = currency,
                primaryCurrencyConfirmed = true
            )
        }
        checkPermissions()
    }

    fun setStep(step: OnboardingStep) {
        _state.value = _state.value.copy(step = step)
        if (
            step == OnboardingStep.PERMISSIONS &&
            smsRepository.hasPermissions() &&
            _state.value.primaryCurrencyConfirmed
        ) {
            unlockAppShell()
        }
    }

    fun selectPrimaryCurrency(currency: String) {
        val normalized = currency.uppercase()
        require(normalized in ProcessingConfigurationRepository.SUPPORTED)
        _state.value = _state.value.copy(selectedPrimaryCurrency = normalized)
    }

    fun confirmPrimaryCurrency() {
        processingConfiguration.confirmPrimaryCurrency(_state.value.selectedPrimaryCurrency)
        _state.value = _state.value.copy(
            step = OnboardingStep.PERMISSIONS,
            primaryCurrencyConfirmed = true
        )
        if (smsRepository.hasPermissions()) unlockAppShell()
    }

    fun checkPermissions() {
        val granted = smsRepository.hasPermissions()
        _state.value = _state.value.copy(hasPermissions = granted)
        if (
            granted && _state.value.step == OnboardingStep.PERMISSIONS &&
            _state.value.primaryCurrencyConfirmed
        ) {
            unlockAppShell()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted && _state.value.primaryCurrencyConfirmed) {
            unlockAppShell()
        } else {
            _state.value = _state.value.copy(
                hasPermissions = false,
                deniedCount = _state.value.deniedCount + 1
            )
        }
    }

    private fun unlockAppShell() {
        setupImportStore.tryCompleteRequiredPermissions() ?: return
        _state.value = _state.value.copy(
            step = OnboardingStep.COMPLETED,
            hasPermissions = true
        )
    }
}
