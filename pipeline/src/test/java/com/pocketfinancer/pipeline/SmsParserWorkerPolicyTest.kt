package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ListenableWorker
import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.repository.SmsIngestionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SmsParserWorkerPolicyTest {
    @Test
    fun `stale terminal settlement neither posts nor cancels a successor`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            coEvery { ingestionRepository.get("opaque-key") } returns
                queuedCandidate(SmsCandidateOrigin.AUTOMATIC).copy(
                    claimToken = null
                )
            var skippedPosted = false
            var notificationCancelled = false

            applyExactTerminalNotification(
                settledOwnedClaim = false,
                onOwned = { skippedPosted = true },
                onStale = {
                    cancelStaleTerminalNotificationIfCandidateAbsent(
                        ingestionRepository = ingestionRepository,
                        candidateKey = "opaque-key"
                    ) {
                        notificationCancelled = true
                    }
                }
            )

            assertFalse(skippedPosted)
            assertFalse(notificationCancelled)
        }

    @Test
    fun `terminal stale cleanup cancels only after the row is absent`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            var notificationCancelled = false
            coEvery { ingestionRepository.get("opaque-key") } returns
                queuedCandidate(SmsCandidateOrigin.AUTOMATIC).copy(
                    claimToken = "same-owner"
                )

            assertFalse(
                cancelStaleTerminalNotificationIfCandidateAbsent(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key"
                ) {
                    notificationCancelled = true
                }
            )
            assertFalse(notificationCancelled)

            coEvery { ingestionRepository.get("opaque-key") } returns null
            assertTrue(
                cancelStaleTerminalNotificationIfCandidateAbsent(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key"
                ) {
                    notificationCancelled = true
                }
            )
            assertTrue(notificationCancelled)
        }

    @Test
    fun `retry settlement failure cleanup cancels a still-owned notification`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            coEvery { ingestionRepository.get("opaque-key") } returns
                queuedCandidate(SmsCandidateOrigin.AUTOMATIC).copy(
                    claimToken = "same-owner"
                )
            var notificationCancelled = false

            assertTrue(
                cancelNotificationAfterRetrySettlementFailure(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key",
                    claimToken = "same-owner"
                ) {
                    notificationCancelled = true
                }
            )
            assertTrue(notificationCancelled)

            notificationCancelled = false
            coEvery { ingestionRepository.get("opaque-key") } returns null
            assertTrue(
                cancelNotificationAfterRetrySettlementFailure(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key",
                    claimToken = "same-owner"
                ) {
                    notificationCancelled = true
                }
            )
            assertTrue(notificationCancelled)
        }

    @Test
    fun `retry settlement failure cleanup preserves ambiguous and replacement rows`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            var notificationCancelled = false
            coEvery { ingestionRepository.get("opaque-key") } returns
                queuedCandidate(SmsCandidateOrigin.AUTOMATIC).copy(
                    claimToken = null
                )

            assertFalse(
                cancelNotificationAfterRetrySettlementFailure(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key",
                    claimToken = "stale-owner"
                ) {
                    notificationCancelled = true
                }
            )

            coEvery { ingestionRepository.get("opaque-key") } returns
                queuedCandidate(SmsCandidateOrigin.AUTOMATIC).copy(
                    claimToken = "replacement-owner"
                )
            assertFalse(
                cancelNotificationAfterRetrySettlementFailure(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key",
                    claimToken = "stale-owner"
                ) {
                    notificationCancelled = true
                }
            )
            assertFalse(notificationCancelled)
        }

    @Test
    fun `exhausted retry cleanup preserves the settlement failure`() =
        runTest {
            val settlementFailure = IllegalStateException("discard failed")
            val cleanupFailure = IllegalArgumentException("cancel failed")
            var cleanupRan = false

            val thrown = assertFailsWith<IllegalStateException> {
                withNotificationCleanupOnSettlementFailure(
                    settle = { throw settlementFailure },
                    cleanup = {
                        cleanupRan = true
                        throw cleanupFailure
                    }
                )
            }

            assertTrue(cleanupRan)
            assertSame(settlementFailure, thrown)
            val suppressed = assertIs<IllegalArgumentException>(
                thrown.suppressed.single()
            )
            assertEquals("cancel failed", suppressed.message)
        }

    @Test
    fun `missing candidate recovery cancels a possibly ongoing notification`() {
        var notificationCancelled = false

        cancelMissingCandidateNotification {
            notificationCancelled = true
        }

        assertTrue(notificationCancelled)
    }

    @Test
    fun `automatic OFF that wins the boundary prevents claim`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            val automaticPreferences =
                mockk<AutomaticProcessingPreferences>()
            coEvery {
                automaticPreferences
                    .withConsistencyBoundary<SmsCandidateClaimDecision>(any())
            } coAnswers {
                firstArg<suspend (Boolean) -> SmsCandidateClaimDecision>()
                    .invoke(false)
            }
            coEvery {
                ingestionRepository.discardAutomaticBeforeClaim(
                    "opaque-key",
                    "work-id"
                )
            } returns true

            val decision = assertIs<SmsCandidateClaimDecision.AutomaticDisabled>(
                claimSmsCandidateForRun(
                    candidate = queuedCandidate(SmsCandidateOrigin.AUTOMATIC),
                    claimToken = "work-id",
                    automaticProcessingPreferences = automaticPreferences,
                    ingestionRepository = ingestionRepository
                )
            )
            assertTrue(decision.discardedCandidate)
            coVerify(exactly = 0) { ingestionRepository.claim(any(), any()) }
            coVerify(exactly = 1) {
                ingestionRepository.discardAutomaticBeforeClaim(
                    "opaque-key",
                    "work-id"
                )
            }
        }

    @Test
    fun `OFF recovery cancels notification only after discarding its candidate`() {
        val context = mockk<Context>()
        mockkObject(SmsNotificationHelper)
        every {
            SmsNotificationHelper.cancelCandidateNotification(context, "opaque-key")
        } returns Unit

        try {
            SmsCandidateClaimDecision.AutomaticDisabled(
                discardedCandidate = true
            ).cancelDiscardedCandidateNotification(
                context = context,
                candidateKey = "opaque-key"
            )
            SmsCandidateClaimDecision.AutomaticDisabled(
                discardedCandidate = false
            ).cancelDiscardedCandidateNotification(
                context = context,
                candidateKey = "opaque-key"
            )

            verify(exactly = 1) {
                SmsNotificationHelper.cancelCandidateNotification(
                    context,
                    "opaque-key"
                )
            }
        } finally {
            unmockkObject(SmsNotificationHelper)
        }
    }

    @Test
    fun `automatic claim that wins boundary is not deleted by later OFF`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            val automaticPreferences =
                mockk<AutomaticProcessingPreferences>()
            val claimed = queuedCandidate(SmsCandidateOrigin.AUTOMATIC)
                .copy(claimToken = "work-a")
            coEvery {
                automaticPreferences
                    .withConsistencyBoundary<SmsCandidateClaimDecision>(any())
            } coAnswers {
                firstArg<suspend (Boolean) -> SmsCandidateClaimDecision>()
                    .invoke(true)
            }
            coEvery {
                ingestionRepository.claim(
                    "opaque-key",
                    "work-a",
                    any(),
                    any()
                )
            } returns claimed

            val decision = assertIs<SmsCandidateClaimDecision.Claimed>(
                claimSmsCandidateForRun(
                    candidate = queuedCandidate(SmsCandidateOrigin.AUTOMATIC),
                    claimToken = "work-a",
                    automaticProcessingPreferences = automaticPreferences,
                    ingestionRepository = ingestionRepository
                )
            )

            assertNotNull(decision.candidate)
            coVerify(exactly = 0) {
                ingestionRepository.discardClaimed(any(), any())
            }
        }

    @Test
    fun `manual claim bypasses automatic OFF boundary`() = runTest {
        val ingestionRepository = mockk<SmsIngestionRepository>()
        val automaticPreferences =
            mockk<AutomaticProcessingPreferences>()
        val manual = queuedCandidate(SmsCandidateOrigin.MANUAL)
            .copy(claimToken = "manual-work")
        coEvery {
            ingestionRepository.claim(
                "opaque-key",
                "manual-work",
                any(),
                any()
            )
        } returns manual

        val decision = assertIs<SmsCandidateClaimDecision.Claimed>(
            claimSmsCandidateForRun(
                candidate = queuedCandidate(SmsCandidateOrigin.MANUAL),
                claimToken = "manual-work",
                automaticProcessingPreferences = automaticPreferences,
                ingestionRepository = ingestionRepository
            )
        )

        assertNotNull(decision.candidate)
        coVerify(exactly = 0) {
            automaticPreferences.withConsistencyBoundary<Any?>(any())
        }
    }

    @Test
    fun `stale owner terminal cleanup cannot delete replacement evidence`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            coEvery {
                ingestionRepository.discardClaimed(
                    candidateKey = "opaque-key",
                    claimToken = "stale-owner"
                )
            } returns false

            assertFalse(
                discardOwnedTerminalCandidate(
                    ingestionRepository = ingestionRepository,
                    candidateKey = "opaque-key",
                    claimToken = "stale-owner"
                )
            )
            coVerify(exactly = 0) {
                ingestionRepository.discardTerminal(any())
            }
        }

    @Test
    fun `lost retry ownership finishes without a futile WorkManager retry`() =
        runTest {
            val ingestionRepository = mockk<SmsIngestionRepository>()
            val automaticPreferences =
                mockk<AutomaticProcessingPreferences>()
            coEvery {
                automaticPreferences
                    .withConsistencyBoundary<SmsCandidateRetrySettlement>(any())
            } coAnswers {
                firstArg<suspend (Boolean) -> SmsCandidateRetrySettlement>()
                    .invoke(true)
            }
            coEvery {
                ingestionRepository.releaseForRetry(
                    candidateKey = "opaque-key",
                    claimToken = "stale-owner",
                    error = "local failure"
                )
            } returns false

            assertEquals(
                SmsCandidateRetrySettlement.FINISHED,
                settleClaimedCandidateForRetry(
                    candidate = queuedCandidate(SmsCandidateOrigin.AUTOMATIC),
                    claimToken = "stale-owner",
                    error = "local failure",
                    automaticProcessingPreferences = automaticPreferences,
                    ingestionRepository = ingestionRepository
                )
            )
        }

    @Test
    fun `model prerequisite retries do not consume operational failure budget`() {
        assertTrue(
            hasRetryBudget(
                SmsCandidateRetryMode.UNTIL_MODEL_PREPARED,
                runAttemptCount = Int.MAX_VALUE
            )
        )
        assertTrue(
            hasRetryBudget(
                SmsCandidateRetryMode.BOUNDED_OPERATIONAL,
                runAttemptCount = MAX_OPERATIONAL_RETRY_ATTEMPTS - 1
            )
        )
        assertFalse(
            hasRetryBudget(
                SmsCandidateRetryMode.BOUNDED_OPERATIONAL,
                runAttemptCount = MAX_OPERATIONAL_RETRY_ATTEMPTS
            )
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancellation waits for non-cancellable settlement and remains cancelled`() =
        runTest {
            val settlementStarted = CompletableDeferred<Unit>()
            val allowSettlement = CompletableDeferred<Unit>()
            var completionCause: Throwable? = null
            val job = launch {
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    rethrowAfterNonCancellableSettlement(cancelled) {
                        settlementStarted.complete(Unit)
                        allowSettlement.await()
                    }
                }
            }
            job.invokeOnCompletion { completionCause = it }
            runCurrent()

            job.cancel(CancellationException("worker stopped"))
            settlementStarted.await()
            runCurrent()

            assertFalse(job.isCompleted)
            allowSettlement.complete(Unit)
            job.join()

            assertTrue(job.isCancelled)
            assertEquals("worker stopped", completionCause?.message)
        }

    @Test
    fun `persisted parser work is terminal when onboarding was reset`() {
        val preferences = mockk<SharedPreferences>()
        every {
            preferences.getBoolean("onboarding_completed", false)
        } returns false

        assertFalse(isOnboardingCompleteForSmsWork(preferences))
    }

    @Test
    fun `exception boundary retries and still propagates cancellation`() = runTest {
        val retry = ListenableWorker.Result.retry()
        var reported: Exception? = null
        val result = protectSmsParserChain(
            onFailure = { reported = it },
            retryOrFinishChain = { retry }
        ) {
            throw IllegalStateException("delegate failed")
        }
        assertEquals("delegate failed", reported?.message)
        assertSame(retry, result)

        assertFailsWith<CancellationException> {
            protectSmsParserChain(
                onFailure = { error("Cancellation must not be reported") },
                retryOrFinishChain = { retry }
            ) {
                throw CancellationException("worker stopped")
            }
        }
    }

    @Test
    fun `candidate fetch and claim exceptions remain retryable until durable settlement`() =
        runTest {
            listOf(
                "encrypted candidate fetch failed",
                "automatic candidate claim failed"
            ).forEach { failureMessage ->
                val retry = ListenableWorker.Result.retry()
                var reported: Exception? = null

                val result = protectSmsParserChain(
                    onFailure = { reported = it },
                    retryOrFinishChain = { retry }
                ) {
                    throw IllegalStateException(failureMessage)
                }

                assertSame(retry, result)
                assertEquals(failureMessage, reported?.message)
            }
        }

    private fun queuedCandidate(
        origin: SmsCandidateOrigin
    ): QueuedSmsCandidate = QueuedSmsCandidate(
        candidateKey = "opaque-key",
        sourceIdentity = SmsSourceIdentity.androidSms(
            providerMessageId = "provider-id",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1,
            receivedTimestamp = 1_100L
        ),
        sender = "AX-HDFCBK",
        rawMessage = "Rs 500 debited",
        date = 1_100L,
        sourceTimestamp = 1_000L,
        messageType = 1,
        origin = origin,
        claimToken = null,
        attemptCount = 0
    )
}
