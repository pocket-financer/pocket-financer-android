package com.pocketfinancer.pipeline

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.repository.SmsIngestionRepository
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsScheduleResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class SmsWorkSchedulerImplTest {
    private val context = mockk<Context>()
    private val workManager = mockk<WorkManager>()
    private val ingestionRepository = mockk<SmsIngestionRepository>()
    private val automaticPreferences = mockk<AutomaticProcessingPreferences>()
    private val enabled = MutableStateFlow(true)
    private lateinit var enqueueOperation: Operation

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        mockkObject(SmsNotificationHelper)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every {
            SmsNotificationHelper.cancelCandidateNotification(
                any(),
                any()
            )
        } returns Unit
        every { WorkManager.getInstance(context) } returns workManager
        every { automaticPreferences.enabled } returns enabled
        coEvery {
            automaticPreferences.withConsistencyBoundary<Any?>(any())
        } coAnswers {
            firstArg<suspend (Boolean) -> Any?>().invoke(enabled.value)
        }
        enqueueOperation = completedOperation()
        every {
            workManager.enqueueUniqueWork(
                any(),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        } returns enqueueOperation
        every { workManager.cancelUniqueWork(any()) } returns completedOperation()
        coEvery {
            ingestionRepository.admit(any())
        } returns SmsIngestionRepository.AdmissionResult.Admitted(
            candidateKey = "sms_opaque",
            newlyCreated = true
        )
        coEvery { ingestionRepository.discardPendingAutomatic() } returns 0
        coEvery {
            ingestionRepository.pendingAutomaticCandidates()
        } returns emptyList()
        coEvery {
            ingestionRepository.pendingAutomaticCandidateKeys()
        } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkObject(WorkManager.Companion)
        unmockkObject(SmsNotificationHelper)
        unmockkStatic(Log::class)
    }

    @Test
    fun `WorkData contains only opaque candidate key`() = runTest {
        val request = slot<OneTimeWorkRequest>()
        val scheduler = SmsWorkSchedulerImpl(
            context,
            SmsWorkAdmissionGate(),
            ingestionRepository,
            automaticPreferences
        )

        val result = scheduler.scheduleSmsParsing(transactionSms())

        assertEquals(SmsScheduleResult.SCHEDULED, result)
        verify {
            workManager.enqueueUniqueWork(
                SmsParserWorker.uniqueWorkName("sms_opaque"),
                ExistingWorkPolicy.KEEP,
                capture(request)
            )
        }
        val input = request.captured.workSpec.input
        assertEquals("sms_opaque", input.getString(SmsParserWorker.KEY_CANDIDATE_KEY))
        assertNull(input.getString("address"))
        assertNull(input.getString("body"))
        assertEquals(0L, input.getLong("date", 0L))
        assertEquals(
            setOf(SmsParserWorker.KEY_CANDIDATE_KEY),
            input.keyValueMap.keys
        )
    }

    @Test
    fun `disabled intake stores no new candidate and prunes pending automatic work`() =
        runTest {
            enabled.value = false
            val scheduler = SmsWorkSchedulerImpl(
                context,
                SmsWorkAdmissionGate(),
                ingestionRepository,
                automaticPreferences
            )

            assertEquals(
                SmsScheduleResult.AUTOMATIC_DISABLED,
                scheduler.scheduleSmsParsing(transactionSms())
            )

            coVerify(exactly = 1) {
                ingestionRepository.discardPendingAutomatic()
            }
            coVerify(exactly = 0) { ingestionRepository.admit(any()) }
            verify(exactly = 0) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }
        }

    @Test
    fun `disabled intake cancels work and notification before deleting evidence`() =
        runTest {
            enabled.value = false
            val pending = pendingCandidate()
            var workCancellationRequested = false
            var notificationCancelled = false
            coEvery {
                ingestionRepository.pendingAutomaticCandidates()
            } returns listOf(pending)
            every {
                workManager.cancelUniqueWork(
                    SmsParserWorker.uniqueWorkName(pending.candidateKey)
                )
            } answers {
                workCancellationRequested = true
                completedOperation()
            }
            every {
                SmsNotificationHelper.cancelCandidateNotification(
                    context,
                    pending.candidateKey
                )
            } answers {
                notificationCancelled = true
            }
            coEvery {
                ingestionRepository.discardPendingAutomatic()
            } coAnswers {
                assertTrue(workCancellationRequested)
                assertTrue(notificationCancelled)
                1
            }
            val scheduler = SmsWorkSchedulerImpl(
                context,
                SmsWorkAdmissionGate(),
                ingestionRepository,
                automaticPreferences
            )

            assertEquals(
                SmsScheduleResult.AUTOMATIC_DISABLED,
                scheduler.scheduleSmsParsing(transactionSms())
            )

            verify(exactly = 1) {
                workManager.cancelUniqueWork(
                    SmsParserWorker.uniqueWorkName(pending.candidateKey)
                )
            }
            verify(exactly = 0) {
                workManager.cancelUniqueWork(
                    SmsParserWorker.uniqueWorkName("claimed-operation")
                )
            }
            verify(exactly = 0) {
                workManager.cancelAllWorkByTag(SmsParserWorker.WORK_TAG)
            }
            verify(exactly = 1) {
                SmsNotificationHelper.cancelCandidateNotification(
                    context,
                    pending.candidateKey
                )
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `disabled cleanup waits for admitted enqueue before deleting pending evidence`() =
        runTest {
            enabled.value = false
            val inFlightFuture = TestListenableFuture<Operation.State.SUCCESS>()
            val inFlightOperation = mockk<Operation> {
                every { result } returns inFlightFuture
            }
            val gate = SmsWorkAdmissionGate()
            assertTrue(gate.enqueueIfOpen { inFlightOperation })
            val scheduler = SmsWorkSchedulerImpl(
                context,
                gate,
                ingestionRepository,
                automaticPreferences
            )

            val cleanup = launch {
                scheduler.scheduleSmsParsing(transactionSms())
            }
            runCurrent()

            coVerify(exactly = 0) {
                ingestionRepository.discardPendingAutomatic()
            }

            inFlightFuture.complete(Operation.SUCCESS)
            cleanup.join()

            coVerify(exactly = 1) {
                ingestionRepository.discardPendingAutomatic()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `disabled cleanup waits for candidate cancellation before deleting evidence`() =
        runTest {
            enabled.value = false
            val pending = pendingCandidate()
            val cancellationFuture = TestListenableFuture<Operation.State.SUCCESS>()
            val cancellationOperation = mockk<Operation> {
                every { result } returns cancellationFuture
            }
            coEvery {
                ingestionRepository.pendingAutomaticCandidates()
            } returns listOf(pending)
            coEvery {
                ingestionRepository.discardPendingAutomatic()
            } returns 1
            every {
                workManager.cancelUniqueWork(
                    SmsParserWorker.uniqueWorkName(pending.candidateKey)
                )
            } returns cancellationOperation
            val scheduler = SmsWorkSchedulerImpl(
                context,
                SmsWorkAdmissionGate(),
                ingestionRepository,
                automaticPreferences
            )

            val cleanup = launch {
                scheduler.scheduleSmsParsing(transactionSms())
            }
            runCurrent()

            verify(exactly = 1) {
                workManager.cancelUniqueWork(
                    SmsParserWorker.uniqueWorkName(pending.candidateKey)
                )
            }
            coVerify(exactly = 0) {
                ingestionRepository.discardPendingAutomatic()
            }

            cancellationFuture.complete(Operation.SUCCESS)
            cleanup.join()

            coVerify(exactly = 1) {
                ingestionRepository.discardPendingAutomatic()
            }
        }

    @Test
    fun `already saved source does not enqueue duplicate work`() = runTest {
        coEvery {
            ingestionRepository.admit(any())
        } returns SmsIngestionRepository.AdmissionResult.AlreadySaved(
            transactionId = "transaction-id",
            candidateKey = "sms_opaque"
        )
        val scheduler = SmsWorkSchedulerImpl(
            context,
            SmsWorkAdmissionGate(),
            ingestionRepository,
            automaticPreferences
        )

        assertEquals(
            SmsScheduleResult.ALREADY_SAVED,
            scheduler.scheduleSmsParsing(transactionSms())
        )
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(
                any(),
                any<ExistingWorkPolicy>(),
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `reset admission pause drops encrypted pending automatic candidate`() =
        runTest {
            val gate = SmsWorkAdmissionGate()
            val pause = gate.pause()
            val scheduler = SmsWorkSchedulerImpl(
                context,
                gate,
                ingestionRepository,
                automaticPreferences
            )

            assertEquals(
                SmsScheduleResult.ADMISSION_PAUSED,
                scheduler.scheduleSmsParsing(transactionSms())
            )
            coVerify(exactly = 1) {
                ingestionRepository.discardPendingAutomatic()
            }
            verify(exactly = 0) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }
            pause.release()
        }

    @Test
    fun `startup recovery enqueues each pending automatic candidate with KEEP`() =
        runTest {
            coEvery {
                ingestionRepository.pendingAutomaticCandidateKeys()
            } returns listOf("oldest", "newest")
            val requests = mutableListOf<OneTimeWorkRequest>()
            val names = mutableListOf<String>()
            every {
                workManager.enqueueUniqueWork(
                    capture(names),
                    ExistingWorkPolicy.KEEP,
                    capture(requests)
                )
            } returns enqueueOperation
            val scheduler = SmsWorkSchedulerImpl(
                context,
                SmsWorkAdmissionGate(),
                ingestionRepository,
                automaticPreferences
            )

            scheduler.reconcilePendingAutomaticWork()

            assertEquals(
                listOf(
                    SmsParserWorker.uniqueWorkName("oldest"),
                    SmsParserWorker.uniqueWorkName("newest")
                ),
                names
            )
            assertEquals(
                listOf("oldest", "newest"),
                requests.map {
                    it.workSpec.input.getString(SmsParserWorker.KEY_CANDIDATE_KEY)
                }
            )
            assertTrue(
                requests.all {
                    it.workSpec.input.keyValueMap.keys ==
                        setOf(SmsParserWorker.KEY_CANDIDATE_KEY)
                }
            )
        }

    @Test
    fun `startup recovery discards pending automatic candidates when disabled`() =
        runTest {
            enabled.value = false
            coEvery {
                ingestionRepository.pendingAutomaticCandidateKeys()
            } returns listOf("must-not-enqueue")
            val scheduler = SmsWorkSchedulerImpl(
                context,
                SmsWorkAdmissionGate(),
                ingestionRepository,
                automaticPreferences
            )

            scheduler.reconcilePendingAutomaticWork()

            coVerify(exactly = 1) {
                ingestionRepository.discardPendingAutomatic()
            }
            coVerify(exactly = 0) {
                ingestionRepository.pendingAutomaticCandidateKeys()
            }
            verify(exactly = 0) {
                workManager.enqueueUniqueWork(
                    any(),
                    any<ExistingWorkPolicy>(),
                    any<OneTimeWorkRequest>()
                )
            }
        }

    @Test
    fun `startup recovery stops and discards remaining pending work if disabled mid pass`() =
        runTest {
            coEvery {
                ingestionRepository.pendingAutomaticCandidateKeys()
            } returns listOf("claimed-boundary", "still-pending")
            every {
                workManager.enqueueUniqueWork(
                    any(),
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>()
                )
            } answers {
                enabled.value = false
                enqueueOperation
            }
            val scheduler = SmsWorkSchedulerImpl(
                context,
                SmsWorkAdmissionGate(),
                ingestionRepository,
                automaticPreferences
            )

            scheduler.reconcilePendingAutomaticWork()

            verify(exactly = 1) {
                workManager.enqueueUniqueWork(
                    SmsParserWorker.uniqueWorkName("claimed-boundary"),
                    ExistingWorkPolicy.KEEP,
                    any<OneTimeWorkRequest>()
                )
            }
            coVerify(exactly = 1) {
                ingestionRepository.discardPendingAutomatic()
            }
        }

    private fun transactionSms() = SmsReader.SmsMessage(
        address = "AX-HDFCBK",
        body = "Rs.500 credited to A/c XX1234",
        date = 1234L,
        type = 1,
        providerMessageId = "42"
    )

    private fun pendingCandidate() = QueuedSmsCandidate(
        candidateKey = "pending-notification",
        sourceIdentity = SmsSourceIdentity.androidSms(
            providerMessageId = null,
            sender = "AX-HDFCBK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        ),
        sender = "AX-HDFCBK",
        rawMessage = "Rs 500 debited",
        date = 1_000L,
        sourceTimestamp = 1_000L,
        messageType = 1,
        origin = SmsCandidateOrigin.AUTOMATIC,
        claimToken = null,
        attemptCount = 0
    )

    private fun completedOperation(): Operation {
        val future = TestListenableFuture<Operation.State.SUCCESS>()
        future.complete(Operation.SUCCESS)
        return mockk {
            every { result } returns future
        }
    }
}

private class TestListenableFuture<T> : ListenableFuture<T> {
    private val monitor = Object()
    private val listeners = mutableListOf<Pair<Runnable, Executor>>()
    private var completed = false
    private var cancelled = false
    private var value: T? = null
    private var failure: Throwable? = null

    fun complete(result: T) {
        finish(result, null, false)
    }

    override fun addListener(listener: Runnable, executor: Executor) {
        val immediate = synchronized(monitor) {
            if (completed) true else false.also { listeners += listener to executor }
        }
        if (immediate) executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        synchronized(monitor) {
            if (completed) return false
        }
        finish(null, null, true)
        return true
    }

    override fun isCancelled(): Boolean = synchronized(monitor) { cancelled }
    override fun isDone(): Boolean = synchronized(monitor) { completed }

    override fun get(): T {
        synchronized(monitor) {
            while (!completed) monitor.wait()
            return resolvedValue()
        }
    }

    override fun get(timeout: Long, unit: TimeUnit): T {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        synchronized(monitor) {
            while (!completed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException()
                val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                val nanos =
                    (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                monitor.wait(millis, nanos)
            }
            return resolvedValue()
        }
    }

    private fun finish(result: T?, error: Throwable?, wasCancelled: Boolean) {
        val callbacks = synchronized(monitor) {
            if (completed) return
            completed = true
            cancelled = wasCancelled
            value = result
            failure = error
            monitor.notifyAll()
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { (listener, executor) -> executor.execute(listener) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolvedValue(): T {
        if (cancelled) throw CancellationException()
        failure?.let { throw ExecutionException(it) }
        return value as T
    }
}
