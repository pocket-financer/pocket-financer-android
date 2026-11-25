package com.pocketfinancer.sms

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsReaderTest {

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver
    private lateinit var reader: SmsReader

    @Before
    fun setUp() {
        mockContext = mockk()
        mockContentResolver = mockk()
        every { mockContext.contentResolver } returns mockContentResolver
        reader = SmsReader(mockContext)
    }

    @Test
    fun `fetchInbox queries inbox URI with full projection and descending sort`() {
        val cursor = MatrixCursor(
            arrayOf("_id", "address", "body", "date", "date_sent", "type")
        )
        cursor.addRow(arrayOf<Any?>(1, "AX-SYNTH", "Rs.500 credited to a/c XX0000", 2000L, 0L, 1))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date", "date_sent", "type"),
                any(),
                any(),
                "date DESC"
            )
        } returns cursor

        val results = reader.fetchInbox()
        assertEquals(1, results.size)
        assertEquals("AX-SYNTH", results[0].address)
        assertEquals("Rs.500 credited to a/c XX0000", results[0].body)
        assertEquals(2000L, results[0].date)
        assertEquals(1, results[0].type)
        assertEquals("1", results[0].providerMessageId)
        assertEquals(2000L, results[0].sourceTimestamp)
    }

    @Test
    fun `provider received date stays visible while sent date converges with broadcast identity`() {
        val cursor = MatrixCursor(
            arrayOf("_id", "address", "body", "date", "date_sent", "type")
        )
        cursor.addRow(arrayOf<Any?>(9, "AX-SYNTH", "Rs.500 credited to a/c XX0000", 2_500L, 2_000L, 1))
        every {
            mockContentResolver.query(Uri.parse("content://sms/inbox"), any(), any(), any(), any())
        } returns cursor

        val provider = reader.fetchInbox().single()
        val broadcast = SmsReader.SmsMessage(
            address = provider.address,
            body = provider.body,
            date = 2_000L,
            type = 1,
            sourceTimestamp = 2_000L
        )

        assertEquals(2_500L, provider.date)
        assertEquals(2_000L, provider.sourceTimestamp)
        assertEquals(
            broadcast.sourceIdentity.fallbackFingerprint,
            provider.sourceIdentity.fallbackFingerprint
        )
        assertEquals(
            SmsReader.SmsMessage(provider.address, provider.body, 2_500L, 1)
                .sourceIdentity.fallbackFingerprint,
            provider.sourceIdentity.alternateFingerprint
        )
    }

    @Test
    fun `fetchInbox handles empty cursor, null columns, and provider failure`() {
        every {
            mockContentResolver.query(Uri.parse("content://sms/inbox"), any(), any(), any(), any())
        } returns MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        assertTrue(reader.fetchInbox().isEmpty())

        val nullRowCursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        nullRowCursor.addRow(arrayOf<Any?>(1, null, null, 1000L, 1))
        every {
            mockContentResolver.query(Uri.parse("content://sms/inbox"), any(), any(), any(), any())
        } returns nullRowCursor
        val row = reader.fetchInbox().single()
        assertEquals("", row.address)
        assertEquals("", row.body)

        every {
            mockContentResolver.query(Uri.parse("content://sms/inbox"), any(), any(), any(), any())
        } returns null
        val error = assertFailsWith<IllegalStateException> { reader.fetchInbox() }
        assertTrue(error.message.orEmpty().contains("SMS provider"))
    }

    @Test
    fun `fetchInbox applies date selection, limit, offset, and regex address filter safely`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        for (i in 1..5) {
            cursor.addRow(arrayOf<Any?>(i, "AX-BANK-$i", "SMS $i", i * 1000L, 1))
        }
        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(),
                "date >= ? AND date <= ?",
                arrayOf("1000", "5000"),
                any()
            )
        } returns cursor

        val paged = reader.fetchInbox(
            SmsReader.SmsFilter(minDate = 1000L, maxDate = 5000L, offset = 2, limit = 2)
        )
        assertEquals(listOf("AX-BANK-3", "AX-BANK-4"), paged.map { it.address })

        val filterCursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        filterCursor.addRow(arrayOf<Any?>(1, "AX-ALPHA", "Alpha SMS", 1000L, 1))
        filterCursor.addRow(arrayOf<Any?>(2, "AX-BETA", "Beta SMS", 2000L, 1))
        every {
            mockContentResolver.query(Uri.parse("content://sms/inbox"), any(), any(), any(), any())
        } returns filterCursor

        val filtered = reader.fetchInbox(SmsReader.SmsFilter(addressPattern = "ALPHA", limit = 10))
        assertEquals(listOf("AX-ALPHA"), filtered.map { it.address })

        val invalidRegexCursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        invalidRegexCursor.addRow(arrayOf<Any?>(1, "AX-ALPHA", "Alpha SMS", 1000L, 1))
        every {
            mockContentResolver.query(Uri.parse("content://sms/inbox"), any(), any(), any(), any())
        } returns invalidRegexCursor

        val fallback = reader.fetchInbox(SmsReader.SmsFilter(addressPattern = "[", limit = 10))
        assertEquals(listOf("AX-ALPHA"), fallback.map { it.address })
    }

    @Test
    fun `hasAnyInboxMessage probes only _id and distinguishes empty vs non-empty inbox`() {
        val populated = MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf("older-message")) }
        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "date <= ?",
                arrayOf("5000"),
                "date DESC"
            )
        } returns populated
        assertTrue(reader.hasAnyInboxMessage(maxDate = 5_000L))

        val empty = MatrixCursor(arrayOf("_id"))
        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "date <= ?",
                arrayOf("5000"),
                "date DESC"
            )
        } returns empty
        assertFalse(reader.hasAnyInboxMessage(maxDate = 5_000L))

        verify(exactly = 2) {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "date <= ?",
                arrayOf("5000"),
                "date DESC"
            )
        }
    }
}
