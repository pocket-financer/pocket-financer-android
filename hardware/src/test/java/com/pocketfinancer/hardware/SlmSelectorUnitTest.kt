package com.pocketfinancer.hardware

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class SlmSelectorUnitTest {

    private fun contextWithSelectedModel(selectedId: String?): Context {
        val preferences = mockk<SharedPreferences>()
        every { preferences.getString("selected_slm_id", null) } returns selectedId
        return mockk {
            every {
                getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
            } returns preferences
        }
    }

    private inline fun withModelDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("slm-selector-models").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    // ── Helper: build a DeviceInfo with given specs ───────────────────

    private fun deviceWith(
        ramGb: Float,
        highPerf: Boolean = false,
        storageFreeGb: Float = 10f
    ): DeviceCapabilities.DeviceInfo {
        val oneGb = 1_073_741_824L
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = (storageFreeGb * oneGb).toLong() * 10,
            availableBytes = (storageFreeGb * oneGb).toLong(),
            usedBytes = (storageFreeGb * oneGb).toLong() * 9
        )
        val cpu = DeviceCapabilities.CpuInfo(
            cores = 8,
            features = if (highPerf) setOf("i8mm", "dotprod", "asimddp", "fphp") else emptySet(),
            hasI8mm = highPerf,
            hasDotProd = highPerf,
            hasFp16 = highPerf,
            socModel = "Exynos 9611"
        )
        val gpu = if (highPerf) {
            DeviceCapabilities.GpuInfo(
                renderer = "Mali-G72 MP3",
                vendor = "ARM",
                version = "OpenGL ES 3.2",
                hasAdreno = false,
                hasMali = true,
                hasPowerVR = false,
                gpuType = "Mali (ARM)"
            )
        } else null
        return DeviceCapabilities.DeviceInfo(
            ramGb = ramGb,
            ramTier = when {
                ramGb >= 3.5f -> DeviceCapabilities.RamTier.OK
                ramGb >= 2.5f -> DeviceCapabilities.RamTier.WARNING
                else -> DeviceCapabilities.RamTier.BLOCKED
            },
            gpu = gpu,
            cpu = cpu,
            storage = storage,
            isHighPerformanceDevice = highPerf
        )
    }

    // ── Gemma preferred over Qwen3 ──────────────────────────────────

    @Test
    fun `selectSlmForDevice should prefer Gemma over Qwen3 with high RAM`() {
        val device = deviceWith(ramGb = 8.0f, highPerf = true)
        val result = selectSlmForDevice(device)
        assertNotNull(result)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, result)
    }

    // ── Tier 1: Gemma 4 E2B Q8_0 with 8GB+ and high-perf CPU ────────

    @Test
    fun `selectSlmForDevice should pick Gemma Q8_0 with 8GB and high-perf CPU`() {
        val device = deviceWith(ramGb = 8.0f, highPerf = true)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, selectSlmForDevice(device))
    }

    // ── Mali GPU devices now get premium tiers (fix for Qualcomm-only gate) ──

    @Test
    fun `selectSlmForDevice should pick Gemma Q8_0 with Mali GPU and i8mm`() {
        // Galaxy S24 Exynos, Pixel 9 Pro Tensor: Mali GPU + i8mm+dotprod
        // Before fix: excluded by Adreno-only gate. After fix: correctly selected
        // based on CPU features (i8mm+dotprod) regardless of GPU vendor.
        val device = deviceWith(ramGb = 8.0f, highPerf = true)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, selectSlmForDevice(device))
    }

    // ── Device without CPU features can't access premium tiers ──

    @Test
    fun `selectSlmForDevice should NOT pick Q8_0 with 4GB but no CPU features`() {
        // Older or budget phones: enough RAM but missing i8mm+dotprod
        // The test helper sets all CPU features to false when highPerf=false.
        val device = deviceWith(ramGb = 4.0f, highPerf = false)
        assertNotEquals(SlmTier.QWEN3_1_7B_Q8_0, selectSlmForDevice(device))
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(device))
    }

    // ── Tier 2: Gemma 4 E2B Q4_K_M with 6GB RAM ────────────────────

    @Test
    fun `selectSlmForDevice should NOT pick Gemma Q4_K_M with 6GB without high-perf CPU`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = false)
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Gemma Q4_K_M with 6GB with high-perf CPU`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, selectSlmForDevice(device))
    }

    // ── Tier 5: Fallback to Qwen3-0.6B with 2.5GB ────────────────────

    @Test
    fun `selectSlmForDevice should pick Qwen3 0_6B with 2_5GB`() {
        val device = deviceWith(ramGb = 2.5f)
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(device))
    }

    // ── Null when too little RAM ─────────────────────────────────────

    @Test
    fun `selectSlmForDevice should return null with less than 2_5GB`() {
        val device = deviceWith(ramGb = 2.0f)
        assertNull(selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should return null with 1GB`() {
        val device = deviceWith(ramGb = 1.0f)
        assertNull(selectSlmForDevice(device))
    }

    // ── explainTierSelection ──────────────────────────────────────────

    @Test
    fun `explainTierSelection should say Selected for picked tier`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = true)
        val tier = SlmTier.QWEN3_1_7B_Q8_0
        val explanation = explainTierSelection(tier, device, isSelected = true)
        assertTrue(explanation.startsWith("Selected"))
    }

    @Test
    fun `explainTierSelection should mention blockers for unavailable tier`() {
        val device = deviceWith(ramGb = 2.0f)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("Not available"))
        assertTrue(explanation.contains("needs"))
    }

    @Test
    fun `explainTierSelection should mention CPU requirement for Qwen3 Q8_0`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = false)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("i8mm") || explanation.contains("CPU") || explanation.contains("dotprod"))
    }

    @Test
    fun `explainTierSelection should say higher-priority for available but not selected`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("higher-priority") || explanation.contains("Available"))
    }

    // ── ALL_TIERS ordering ────────────────────────────────────────────

    @Test
    fun `ALL_TIERS should be ordered by priority`() {
        val tiers = SlmTier.ALL_TIERS
        assertEquals(5, tiers.size)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, tiers[0])
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, tiers[1])
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, tiers[2])
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, tiers[3])
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, tiers[4])
    }

    @Test
    fun `sizeGb should convert MB to GB`() {
        assertEquals(700.0f / 1024.0f, SlmTier.QWEN3_0_6B_Q8_0.sizeGb, 0.001f)
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3-1_7B Q8_0 with 4GB RAM and high performance`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = true)
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3-0_6B Q8_0 at 3_5GB RAM without high performance`() {
        val device = deviceWith(ramGb = 3.5f, highPerf = false)
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3-1_7B Q4_K_M at exactly 3_5GB RAM boundary with high performance`() {
        val device = deviceWith(ramGb = 3.5f, highPerf = true)
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3-0_6B Q8_0 at exactly 4_0GB RAM boundary without high performance`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = false)
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(device))
    }

    @Test
    fun `explainTierSelection should say Selected for Gemma Q4_K_M`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        val explanation = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, device, isSelected = true)
        assertTrue(explanation.contains("Selected") && explanation.contains("balanced size/quality"))
    }

    @Test
    fun `explainTierSelection should say Selected with CPU info for Gemma Q4_K_M with CPU features`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        val explanation = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, device, isSelected = true)
        assertTrue(explanation.contains("Selected") && explanation.contains("high-perf CPU"))
    }

    @Test
    fun `explainTierSelection should mention CPU requirement for Gemma Q4_K_M`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = false)
        val explanation = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, device, isSelected = false)
        assertTrue(explanation.contains("Not available") && explanation.contains("i8mm+dotprod"))
    }

    @Test
    fun `SlmTier properties should match configuration specifications`() {
        val tier = SlmTier.GEMMA4_E2B_Q8_0
        assertEquals("gemma4-e2b-q8_0", tier.id)
        assertEquals("gemma-4-E2B-it-Q8_0.gguf", tier.modelFile)
        assertEquals(5170, tier.sizeMb)
        assertEquals(8.0f, tier.minRamGb)
        assertEquals("https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q8_0.gguf", tier.downloadUrl)
        assertEquals("gemma4", tier.family)
    }

    @Test
    fun `DEFAULT_ONBOARDING_SLM should be QWEN3_0_6B_Q8_0`() {
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, SlmTier.DEFAULT_ONBOARDING_SLM)
    }

    @Test
    fun `isUpgradeAvailable should return true when device supports higher tier than current`() {
        val highSpecDevice = deviceWith(ramGb = 8.0f, highPerf = true)
        val defaultSlm = SlmTier.DEFAULT_ONBOARDING_SLM
        assertTrue(isUpgradeAvailable(defaultSlm, highSpecDevice))
    }

    @Test
    fun `isUpgradeAvailable should return false when current model matches device max recommendation`() {
        val highSpecDevice = deviceWith(ramGb = 8.0f, highPerf = true)
        val bestSlm = SlmTier.GEMMA4_E2B_Q8_0
        assertFalse(isUpgradeAvailable(bestSlm, highSpecDevice))
    }

    @Test
    fun `published onboarding artifact below approximate tier size is selected`() =
        withModelDirectory { directory ->
            val tier = SlmTier.DEFAULT_ONBOARDING_SLM
            val artifact = File(directory, tier.modelFile)
            RandomAccessFile(artifact, "rw").use {
                it.setLength(639_447_744L)
            }

            assertTrue(isPublishedModelArtifact(artifact))
            assertEquals(
                tier,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(tier.id),
                    modelStorageDir = directory,
                    device = deviceWith(ramGb = 8.0f, highPerf = true)
                )
            )
        }

    @Test
    fun `default published artifact can be smaller than approximate tier size`() =
        withModelDirectory { directory ->
            val tier = SlmTier.DEFAULT_ONBOARDING_SLM
            File(directory, tier.modelFile).writeBytes(byteArrayOf(1))

            assertEquals(
                tier,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(null),
                    modelStorageDir = directory,
                    device = deviceWith(ramGb = 8.0f, highPerf = true)
                )
            )
        }

    @Test
    fun `downloaded non-default published artifact is selected`() =
        withModelDirectory { directory ->
            val tier = SlmTier.QWEN3_1_7B_Q8_0
            File(directory, tier.modelFile).writeBytes(byteArrayOf(1))

            assertEquals(
                tier,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(null),
                    modelStorageDir = directory,
                    device = deviceWith(ramGb = 2.0f)
                )
            )
        }

    @Test
    fun `missing empty and directory artifacts are not published models`() =
        withModelDirectory { directory ->
            val tier = SlmTier.DEFAULT_ONBOARDING_SLM
            val artifact = File(directory, tier.modelFile)
            assertFalse(isPublishedModelArtifact(artifact))

            artifact.createNewFile()
            assertFalse(isPublishedModelArtifact(artifact))
            assertNull(
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(tier.id),
                    modelStorageDir = directory,
                    device = deviceWith(ramGb = 2.0f)
                )
            )

            artifact.delete()
            artifact.mkdir()
            assertFalse(isPublishedModelArtifact(artifact))
            assertNull(
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(tier.id),
                    modelStorageDir = directory,
                    device = deviceWith(ramGb = 2.0f)
                )
            )
        }
}
