package com.pocketfinancer.hardware

import android.app.ActivityManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceCapabilitiesTest {

    private lateinit var mockContext: Context
    private lateinit var mockActivityManager: ActivityManager
    private lateinit var capabilities: DeviceCapabilities

    @Before
    fun setUp() {
        mockContext = mockk()
        mockActivityManager = mockk()
        every { mockContext.getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager
        every { mockContext.filesDir } returns File("/data/data/com.pocketfinancer/files")
        capabilities = DeviceCapabilities(mockContext)
    }

    @Test
    fun `ramTierFor enforces BLOCKED, WARNING, and OK thresholds`() {
        assertEquals(DeviceCapabilities.RamTier.BLOCKED, capabilities.ramTierFor(1.0f))
        assertEquals(DeviceCapabilities.RamTier.BLOCKED, capabilities.ramTierFor(2.4999f))
        assertEquals(DeviceCapabilities.RamTier.WARNING, capabilities.ramTierFor(2.5f))
        assertEquals(DeviceCapabilities.RamTier.WARNING, capabilities.ramTierFor(3.49f))
        assertEquals(DeviceCapabilities.RamTier.OK, capabilities.ramTierFor(3.5f))
        assertEquals(DeviceCapabilities.RamTier.OK, capabilities.ramTierFor(8.0f))
    }

    @Test
    fun `checkRamGb converts ActivityManager totalMem bytes to gigabytes`() {
        listOf(4, 6, 8).forEach { expectedGb ->
            val memInfoSlot = mutableListOf<ActivityManager.MemoryInfo>()
            every { mockActivityManager.getMemoryInfo(capture(memInfoSlot)) } answers {
                memInfoSlot.last().totalMem = expectedGb.toLong() * 1024L * 1024L * 1024L
            }

            assertEquals(expectedGb.toFloat(), capabilities.checkRamGb(), 0.1f)
        }
    }

    @Test
    fun `StorageInfo canFit requires 10 percent safety buffer`() {
        val required = DeviceCapabilities.MODEL_SIZE_BYTES
        val tightStorage = DeviceCapabilities.StorageInfo(
            totalBytes = 100_000_000_000L,
            availableBytes = (required * 1.05).toLong(),
            usedBytes = 0L
        )
        val exactBufferStorage = DeviceCapabilities.StorageInfo(
            totalBytes = 100_000_000_000L,
            availableBytes = (required * 1.1).toLong(),
            usedBytes = 0L
        )
        val roomyStorage = DeviceCapabilities.StorageInfo(
            totalBytes = 50_000_000_000L,
            availableBytes = 5_000_000_000L,
            usedBytes = 45_000_000_000L
        )

        assertFalse(tightStorage.canFit(required))
        assertTrue(exactBufferStorage.canFit(required))
        assertTrue(roomyStorage.canFit(required))
    }

    @Test
    fun `StorageInfo converts bytes to gibibytes accurately`() {
        val oneGib = 1_073_741_824L
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = oneGib * 64,
            availableBytes = oneGib * 10,
            usedBytes = oneGib * 54
        )

        assertEquals(64.0f, storage.totalGb, 0.01f)
        assertEquals(10.0f, storage.availableGb, 0.01f)
        assertEquals(54.0f, storage.usedGb, 0.01f)
    }

    @Test
    fun `capability constants match documented hardware gates`() {
        assertEquals(1_900_000_000L, DeviceCapabilities.MODEL_SIZE_BYTES)
        assertEquals(2.5f, DeviceCapabilities.RAM_BLOCK_GB)
        assertEquals(3.5f, DeviceCapabilities.RAM_WARN_GB)
    }
}
