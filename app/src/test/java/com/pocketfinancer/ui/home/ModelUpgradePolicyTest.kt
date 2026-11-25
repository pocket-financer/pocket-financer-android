package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.ui.onboarding.OnboardingStep
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUpgradePolicyTest {

    @Test
    fun `release policy keeps an unaccelerated device on the starter model`() {
        val target = selectModelUpgradeTarget(
            device = device(
                ramGb = 16f,
                highPerformance = false,
                availableStorageBytes = 10L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = false
        )

        assertEquals(SlmTier.DEFAULT_ONBOARDING_SLM, target.tier)
        assertFalse(target.isDebugEmulatorOverride)
    }

    @Test
    fun `debug emulator override is capped at the 1_7B Q4 model`() {
        val target = selectModelUpgradeTarget(
            device = device(
                ramGb = 16f,
                highPerformance = false,
                availableStorageBytes = 10L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = true
        )

        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, target.tier)
        assertTrue(target.isDebugEmulatorOverride)
    }

    @Test
    fun `debug emulator override respects target RAM and storage`() {
        val lowRam = selectModelUpgradeTarget(
            device = device(
                ramGb = 3f,
                highPerformance = false,
                availableStorageBytes = 10L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = true
        )
        val lowStorage = selectModelUpgradeTarget(
            device = device(
                ramGb = 16f,
                highPerformance = false,
                availableStorageBytes = 1L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = true
        )

        assertEquals(SlmTier.DEFAULT_ONBOARDING_SLM, lowRam.tier)
        assertEquals(SlmTier.DEFAULT_ONBOARDING_SLM, lowStorage.tier)
        assertFalse(lowRam.isDebugEmulatorOverride)
        assertFalse(lowStorage.isDebugEmulatorOverride)
    }

    @Test
    fun `downloaded target remains an upgrade until selection changes`() {
        assertTrue(
            isHigherQualityModel(
                current = SlmTier.DEFAULT_ONBOARDING_SLM,
                target = SlmTier.QWEN3_1_7B_Q4_K_M
            )
        )
        assertFalse(
            isHigherQualityModel(
                current = SlmTier.QWEN3_1_7B_Q4_K_M,
                target = SlmTier.QWEN3_1_7B_Q4_K_M
            )
        )
    }

    @Test
    fun `other model work blocks a new upgrade on every surface`() {
        val message = modelUpgradeStartBlockedMessage(
            onboarding = OnboardingSyncManager.OnboardingSyncState(),
            otherFlowBusy = true
        )

        assertEquals(
            "Finish the current model task before starting a model upgrade.",
            message
        )
    }

    @Test
    fun `active model upgrade can be cancelled while initial setup or completed runs cannot`() {
        val activeUpgrade = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            isCancellationAllowed = true,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
        )
        assertTrue(canCancelModelUpgrade(activeUpgrade))

        val initialSetup = activeUpgrade.copy(
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
        )
        assertFalse(canCancelModelUpgrade(initialSetup))

        val completedUpgrade = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            step = OnboardingStep.COMPLETED
        )
        assertFalse(canCancelModelUpgrade(completedUpgrade))
        assertNull(unfinishedModelUpgradeTarget(completedUpgrade))

        val drainingUpgrade = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            isCancelling = true,
            isCancellationAllowed = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            syncMessage = "Cancelling model upgrade..."
        )
        assertFalse(canCancelModelUpgrade(drainingUpgrade))
    }

    @Test
    fun `cancelled upgrade keeps its target for resume while initial onboarding does not`() {
        val target = SlmTier.QWEN3_1_7B_Q4_K_M
        val cancelledUpgrade = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            selectedSlm = target,
            step = OnboardingStep.DOWNLOAD_SLM,
            syncMessage = "Cancelled"
        )
        assertEquals(target, unfinishedModelUpgradeTarget(cancelledUpgrade))

        val initialSetup = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
            selectedSlm = SlmTier.DEFAULT_ONBOARDING_SLM,
            step = OnboardingStep.DOWNLOAD_SLM
        )
        assertNull(unfinishedModelUpgradeTarget(initialSetup))
    }

    @Test
    fun `session dismissal store scopes dismissals per instance and stays thread-safe`() =
        runBlocking {
            val session = ModelUpgradeSessionDismissalStore()
            session.dismiss("model-a")
            session.dismiss("model-a")

            assertEquals(setOf("model-a"), session.dismissedTierIds.value)
            assertFalse("model-b" in session.dismissedTierIds.value)
            assertEquals(emptySet<String>(), ModelUpgradeSessionDismissalStore().dismissedTierIds.value)

            val concurrent = ModelUpgradeSessionDismissalStore()
            val targetIds = (0 until 512).map { "model-$it" }
            val start = CompletableDeferred<Unit>()
            val jobs = targetIds.map { targetId ->
                launch(Dispatchers.Default) {
                    start.await()
                    concurrent.dismiss(targetId)
                }
            }

            start.complete(Unit)
            jobs.joinAll()

            assertEquals(targetIds.toSet(), concurrent.dismissedTierIds.value)
        }

    private fun device(
        ramGb: Float,
        highPerformance: Boolean,
        availableStorageBytes: Long
    ) = DeviceCapabilities.DeviceInfo(
        ramGb = ramGb,
        ramTier = when {
            ramGb < 2.5f -> DeviceCapabilities.RamTier.BLOCKED
            ramGb < 3.5f -> DeviceCapabilities.RamTier.WARNING
            else -> DeviceCapabilities.RamTier.OK
        },
        gpu = null,
        cpu = DeviceCapabilities.CpuInfo(
            cores = 4,
            features = emptySet(),
            hasI8mm = highPerformance,
            hasDotProd = highPerformance,
            hasFp16 = highPerformance,
            socModel = null
        ),
        storage = DeviceCapabilities.StorageInfo(
            totalBytes = 20L * GIBIBYTE,
            availableBytes = availableStorageBytes,
            usedBytes = 20L * GIBIBYTE - availableStorageBytes
        ),
        isHighPerformanceDevice = highPerformance
    )

    private companion object {
        const val GIBIBYTE = 1_073_741_824L
    }
}
