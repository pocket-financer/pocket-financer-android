package com.pocketfinancer.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsRepositoryTest {

    private lateinit var mockContext: Context
    private lateinit var mockReader: SmsReader
    private lateinit var repo: SmsRepository

    @Before
    fun setUp() {
        mockContext = mockk()
        mockReader = mockk()
        repo = SmsRepository(mockContext, mockReader)
    }

    @Test
    fun `hasPermissions requires both READ_SMS and RECEIVE_SMS`() {
        setPermissions(read = true, receive = true)
        assertTrue(repo.hasPermissions())

        setPermissions(read = false, receive = true)
        assertFalse(repo.hasPermissions())

        setPermissions(read = true, receive = false)
        assertFalse(repo.hasPermissions())

        setPermissions(read = false, receive = false)
        assertFalse(repo.hasPermissions())
    }

    @Test
    fun `fetchHistory binds minDate, maxDate, and limit to SmsReader filter`() {
        val testMessages = listOf(
            SmsReader.SmsMessage("AX-SYNTH", "Rs.500 credited", 1000L, 1)
        )
        val filter = slot<SmsReader.SmsFilter>()
        every { mockReader.fetchInbox(capture(filter)) } returns testMessages

        val maxDate = TimeUnit.DAYS.toMillis(100)
        val results = repo.fetchHistory(daysBack = 30, limit = 100, maxDate = maxDate)

        assertEquals(testMessages, results)
        assertEquals(TimeUnit.DAYS.toMillis(70), filter.captured.minDate)
        assertEquals(maxDate, filter.captured.maxDate)
        assertEquals(100, filter.captured.limit)
        verify(exactly = 1) { mockReader.fetchInbox(any()) }
    }

    @Test
    fun `hasAnyInboxMessage forwards the fixed upper bound`() {
        every { mockReader.hasAnyInboxMessage(9_000L) } returns true
        assertTrue(repo.hasAnyInboxMessage(maxDate = 9_000L))
    }

    @Test
    fun `pollInbox periodically queries inbox and emits batches`() = runBlocking {
        val msg1 = SmsReader.SmsMessage("AX-SYNTH", "Msg 1", 1000L, 1)
        val msg2 = SmsReader.SmsMessage("AX-SYNTH", "Msg 2", 2000L, 1)

        var queryCount = 0
        every { mockReader.fetchInbox(any()) } answers {
            queryCount++
            if (queryCount == 1) listOf(msg1) else listOf(msg2)
        }

        repo.pollInbox(intervalMs = 10L).take(2).test {
            assertEquals(listOf(msg1), awaitItem())
            assertEquals(listOf(msg2), awaitItem())
            awaitComplete()
        }
    }

    private fun setPermissions(read: Boolean, receive: Boolean) {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.READ_SMS)
        } returns if (read) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECEIVE_SMS)
        } returns if (receive) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }
}
