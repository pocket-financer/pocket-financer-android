package com.pocketfinancer.hardware

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SlmSelectorTest {

    @Test
    fun `selectSlmForDevice maps RAM and CPU capability matrix deterministically`() {
        // High-performance ARMv8.2+ CPUs (i8mm + dotprod) unlock Gemma 4 and Qwen3-1.7B tiers
        // regardless of GPU vendor (Mali, Adreno, or none).
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 8.0f, highPerf = true)))
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, selectSlmForDevice(deviceWith(ramGb = 6.0f, highPerf = true)))
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 4.0f, highPerf = true)))
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(deviceWith(ramGb = 3.5f, highPerf = true)))
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 2.5f, highPerf = true)))

        // Devices lacking i8mm+dotprod fall back to the lightweight 0.6B tier at 2.5GB+.
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 8.0f, highPerf = false)))
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 6.0f, highPerf = false)))
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 4.0f, highPerf = false)))
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 3.5f, highPerf = false)))
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(deviceWith(ramGb = 2.5f, highPerf = false)))

        // Devices below 2.5GB RAM are blocked.
        assertNull(selectSlmForDevice(deviceWith(ramGb = 2.49f, highPerf = true)))
        assertNull(selectSlmForDevice(deviceWith(ramGb = 2.0f, highPerf = false)))
        assertNull(selectSlmForDevice(deviceWith(ramGb = 1.0f, highPerf = false)))
    }

    @Test
    fun `explainTierSelection distinguishes selected, blocked, and lower-priority tiers`() {
        val highPerf6Gb = deviceWith(ramGb = 6.0f, highPerf = true)
        val noAccel6Gb = deviceWith(ramGb = 6.0f, highPerf = false)
        val lowRam2Gb = deviceWith(ramGb = 2.0f, highPerf = false)

        val selectedGemma = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, highPerf6Gb, isSelected = true)
        assertTrue(selectedGemma.startsWith("Selected"))
        assertTrue(selectedGemma.contains("high-perf CPU"))

        val selectedQwen = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, deviceWith(4.0f, true), isSelected = true)
        assertTrue(selectedQwen.startsWith("Selected"))

        val lowerPriority = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, highPerf6Gb, isSelected = false)
        assertTrue(lowerPriority.contains("higher-priority"))

        val cpuBlocked = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, noAccel6Gb, isSelected = false)
        assertTrue(cpuBlocked.contains("Not available"))
        assertTrue(cpuBlocked.contains("i8mm+dotprod"))

        val ramAndCpuBlocked = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, lowRam2Gb, isSelected = false)
        assertTrue(ramAndCpuBlocked.contains("Not available"))
        assertTrue(ramAndCpuBlocked.contains("RAM"))
        assertTrue(ramAndCpuBlocked.contains("i8mm+dotprod"))
    }

    @Test
    fun `ALL_TIERS metadata, ordering, and onboarding default remain consistent`() {
        assertEquals(
            listOf(
                SlmTier.GEMMA4_E2B_Q8_0,
                SlmTier.GEMMA4_E2B_Q4_K_M,
                SlmTier.QWEN3_1_7B_Q8_0,
                SlmTier.QWEN3_1_7B_Q4_K_M,
                SlmTier.QWEN3_0_6B_Q8_0
            ),
            SlmTier.ALL_TIERS
        )
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, SlmTier.DEFAULT_ONBOARDING_SLM)
        assertEquals(700.0f / 1024.0f, SlmTier.QWEN3_0_6B_Q8_0.sizeGb, 0.001f)

        val flagship = SlmTier.GEMMA4_E2B_Q8_0
        assertEquals("gemma4-e2b-q8_0", flagship.id)
        assertEquals("gemma-4-E2B-it-Q8_0.gguf", flagship.modelFile)
        assertEquals(5170, flagship.sizeMb)
        assertEquals(8.0f, flagship.minRamGb)
        assertEquals("gemma4", flagship.family)
        assertTrue(flagship.downloadUrl.startsWith("https://huggingface.co/"))
    }

    @Test
    fun `isUpgradeAvailable reflects priority gap and blocked devices`() {
        val highSpec = deviceWith(ramGb = 8.0f, highPerf = true)
        val blocked = deviceWith(ramGb = 2.0f, highPerf = false)

        assertTrue(isUpgradeAvailable(SlmTier.DEFAULT_ONBOARDING_SLM, highSpec))
        assertTrue(isUpgradeAvailable(null, highSpec))
        assertFalse(isUpgradeAvailable(SlmTier.GEMMA4_E2B_Q8_0, highSpec))
        assertFalse(isUpgradeAvailable(SlmTier.DEFAULT_ONBOARDING_SLM, blocked))
    }

    @Test
    fun `resolveActiveSlmTier prefers saved, then default, then downloaded artifact, then hardware`() =
        withModelDirectory { directory ->
            val highSpec = deviceWith(ramGb = 8.0f, highPerf = true)
            val blocked = deviceWith(ramGb = 2.0f, highPerf = false)

            // 1. Saved preference wins when published artifact is non-empty, even below approximate sizeMb.
            val onboardingArtifact = File(directory, SlmTier.DEFAULT_ONBOARDING_SLM.modelFile)
            RandomAccessFile(onboardingArtifact, "rw").use { it.setLength(639_447_744L) }
            assertTrue(isPublishedModelArtifact(onboardingArtifact))
            assertEquals(
                SlmTier.DEFAULT_ONBOARDING_SLM,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(SlmTier.DEFAULT_ONBOARDING_SLM.id),
                    modelStorageDir = directory,
                    device = highSpec
                )
            )

            // 2. Without saved preference, default onboarding artifact on disk wins over hardware recommendation.
            assertEquals(
                SlmTier.DEFAULT_ONBOARDING_SLM,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(null),
                    modelStorageDir = directory,
                    device = highSpec
                )
            )

            // 3. When default artifact is absent, highest-priority downloaded artifact on disk wins.
            onboardingArtifact.delete()
            File(directory, SlmTier.QWEN3_1_7B_Q8_0.modelFile).writeBytes(byteArrayOf(1))
            assertEquals(
                SlmTier.QWEN3_1_7B_Q8_0,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(null),
                    modelStorageDir = directory,
                    device = blocked
                )
            )

            // 4. With no published artifact on disk, falls back to hardware recommendation.
            File(directory, SlmTier.QWEN3_1_7B_Q8_0.modelFile).delete()
            assertEquals(
                SlmTier.GEMMA4_E2B_Q8_0,
                resolveActiveSlmTier(
                    context = contextWithSelectedModel(null),
                    modelStorageDir = directory,
                    device = highSpec
                )
            )
        }

    @Test
    fun `missing, empty, and directory entries are never treated as published model artifacts`() =
        withModelDirectory { directory ->
            val tier = SlmTier.DEFAULT_ONBOARDING_SLM
            val blocked = deviceWith(ramGb = 2.0f)
            val artifact = File(directory, tier.modelFile)

            assertFalse(isPublishedModelArtifact(artifact))

            artifact.createNewFile()
            assertFalse(isPublishedModelArtifact(artifact))
            assertNull(resolveActiveSlmTier(contextWithSelectedModel(tier.id), directory, blocked))

            artifact.delete()
            artifact.mkdir()
            assertFalse(isPublishedModelArtifact(artifact))
            assertNull(resolveActiveSlmTier(contextWithSelectedModel(tier.id), directory, blocked))
        }

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
            socModel = "Synthetic-SoC"
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
        } else {
            null
        }
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
}
