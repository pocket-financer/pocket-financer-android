package com.pocketfinancer.sms

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.internal.GeneratedComponent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class SmsReceiverTest {

    private lateinit var scheduler: SmsWorkScheduler
    private lateinit var context: Context

    private class FakeEntryPointApplication(
        private val workScheduler: SmsWorkScheduler
    ) : Application(), GeneratedComponent, SmsReceiver.SmsReceiverEntryPoint {
        override fun getApplicationContext(): Context = this
        override fun smsWorkScheduler(): SmsWorkScheduler = workScheduler
    }

    @Before
    fun setUp() {
        scheduler = mockk()
        val fakeApp = FakeEntryPointApplication(scheduler)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns fakeApp
    }

    @Test
    fun `ignores non-SMS actions and empty PDU lists without scheduling`() {
        val receiver = SmsReceiver()

        receiver.onReceive(context, Intent("com.example.OTHER"))

        val emptySmsIntent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", emptyArray<Any>())
            putExtra("format", "3gpp")
        }
        receiver.onReceive(context, emptySmsIntent)

        coVerify(exactly = 0) { scheduler.scheduleSmsParsing(any()) }
    }

    @Test
    fun `concatenates multipart SMS PDUs, schedules parsing, and finishes pending result`() {
        val finished = CountDownLatch(1)
        val pendingResult = mockk<BroadcastReceiver.PendingResult>()
        every { pendingResult.finish() } answers { finished.countDown() }

        val captured = slot<SmsReader.SmsMessage>()
        coEvery { scheduler.scheduleSmsParsing(capture(captured)) } returns SmsScheduleResult.SCHEDULED

        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra(
                "pdus",
                arrayOf<Any>(
                    buildDeliverPdu("15550101234", "INR 250.00 debited from "),
                    buildDeliverPdu("15550101234", "A/c XX1234 at DEMO SHOP.")
                )
            )
            putExtra("format", "3gpp")
        }

        val receiver = SmsReceiver().also {
            ReflectionHelpers.setField(BroadcastReceiver::class.java, it, "mPendingResult", pendingResult)
        }
        receiver.onReceive(context, intent)

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals("+15550101234", captured.captured.address)
        assertEquals("INR 250.00 debited from A/c XX1234 at DEMO SHOP.", captured.captured.body)
        assertEquals(captured.captured.date, captured.captured.sourceTimestamp)
        assertTrue(captured.captured.date > 0L)
        assertEquals(1, captured.captured.type)
    }

    @Test
    fun `finishes pending result even when scheduling throws`() {
        val finished = CountDownLatch(1)
        val pendingResult = mockk<BroadcastReceiver.PendingResult>()
        every { pendingResult.finish() } answers { finished.countDown() }
        coEvery { scheduler.scheduleSmsParsing(any()) } throws IllegalStateException("scheduler unavailable")

        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf<Any>(buildDeliverPdu("15550109999", "INR 10.00 debited")))
            putExtra("format", "3gpp")
        }

        val receiver = SmsReceiver().also {
            ReflectionHelpers.setField(BroadcastReceiver::class.java, it, "mPendingResult", pendingResult)
        }
        receiver.onReceive(context, intent)

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        coVerify(exactly = 1) { scheduler.scheduleSmsParsing(any()) }
    }

    /** Builds a valid 3GPP GSM SMS-DELIVER PDU using UCS-2 (UTF-16BE) user data encoding. */
    private fun buildDeliverPdu(numericAddress: String, message: String): ByteArray {
        val out = ByteArrayOutputStream()
        // SMSC length = 0 (use default)
        out.write(0x00)
        // First octet: SMS-DELIVER (0x04)
        out.write(0x04)
        // Originating address: length in digits, type 0x91 (international), swapped semi-octets
        out.write(numericAddress.length)
        out.write(0x91)
        val padded = if (numericAddress.length % 2 == 1) "${numericAddress}F" else numericAddress
        for (i in padded.indices step 2) {
            val lo = Character.digit(padded[i], 16)
            val hi = Character.digit(padded[i + 1], 16)
            out.write((hi shl 4) or lo)
        }
        // TP-PID = 0x00, TP-DCS = 0x08 (UCS-2)
        out.write(0x00)
        out.write(0x08)
        // TP-SCTS: 2026-05-15 10:30:00 UTC (swapped BCD semi-octets)
        out.write(byteArrayOf(0x62, 0x50, 0x51, 0x01, 0x03, 0x00, 0x00))
        val userData = message.toByteArray(Charsets.UTF_16BE)
        out.write(userData.size)
        out.write(userData)
        return out.toByteArray()
    }
}
