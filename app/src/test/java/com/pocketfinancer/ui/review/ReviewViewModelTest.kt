package com.pocketfinancer.ui.review

import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.repository.SmsFieldCorrection
import com.pocketfinancer.data.repository.SmsFieldGroundingClassification
import com.pocketfinancer.data.repository.SmsReviewAction
import com.pocketfinancer.data.repository.SmsReviewCommand
import com.pocketfinancer.data.repository.SmsReviewDetails
import com.pocketfinancer.data.repository.SmsReviewRepository
import com.pocketfinancer.pipeline.PipelineService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `formatReviewAmount displays minor units according to currency scale`() {
        assertEquals("Amount: INR 125.00", formatReviewAmount(12_500, "INR"))
        assertEquals("Amount: INR 0.01", formatReviewAmount(1, "inr"))
        assertEquals("Amount: JPY 1500", formatReviewAmount(1_500, "JPY"))
        assertEquals("Amount: USD 42.50", formatReviewAmount(4_250, "USD"))
        assertEquals("Amount: Unassigned", formatReviewAmount(null, "INR"))
        assertEquals("Amount: Unassigned", formatReviewAmount(100, "ZZZ"))
    }

    @Test
    fun `loadInbox and loadDetails surface data and sanitize repository errors`() =
        runTest(testDispatcher) {
            val repository = mockk<SmsReviewRepository>()
            val caseItem = mockk<SmsReviewCaseEntity>(relaxed = true)
            coEvery { repository.openCases() } returns listOf(caseItem)
            val details = mockDetails("review-1", revision = 2L)
            coEvery { repository.details("review-1") } returns details

            val model = ReviewViewModel(repository, mockk())
            model.loadInbox()
            runCurrent()
            assertFalse(model.state.value.loading)
            assertEquals(listOf(caseItem), model.state.value.cases)
            assertNull(model.state.value.error)

            model.loadDetails("review-1")
            runCurrent()
            assertEquals(details, model.state.value.details)
            assertFalse(model.state.value.actionCompleted)

            coEvery { repository.openCases() } throws IllegalStateException("sqlcipher raw error")
            model.loadInbox()
            runCurrent()
            assertNotNull(model.state.value.error)
            assertFalse(model.state.value.error!!.contains("sqlcipher"))
        }

    @Test
    fun `repeated actions cannot race an in-flight or completed save`() = runTest(testDispatcher) {
        val repository = mockk<SmsReviewRepository>(relaxed = true)
        val details = mockDetails("review", revision = 0L)
        coEvery { repository.details("review") } returns details
        val completion = CompletableDeferred<Unit>()
        coEvery { repository.resolve(any(), any()) } coAnswers {
            completion.await()
            mockk(relaxed = true)
        }
        val model = ReviewViewModel(repository, mockk())
        model.loadDetails("review")
        runCurrent()

        model.resolve(SmsReviewAction.REJECT)
        model.resolve(SmsReviewAction.REJECT)
        runCurrent()
        coVerify(exactly = 1) { repository.resolve(any(), any()) }

        completion.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.actionCompleted)

        model.resolve(SmsReviewAction.REJECT)
        runCurrent()
        coVerify(exactly = 1) { repository.resolve(any(), any()) }
    }

    @Test
    fun `save draft refreshes details without marking action completed`() =
        runTest(testDispatcher) {
            val repository = mockk<SmsReviewRepository>(relaxed = true)
            val initial = mockDetails("review-draft", revision = 1L)
            val refreshed = mockDetails("review-draft", revision = 2L)
            coEvery { repository.details("review-draft") } returnsMany listOf(initial, refreshed)
            coEvery { repository.resolve(any(), any()) } returns mockk(relaxed = true)

            val model = ReviewViewModel(repository, mockk())
            model.loadDetails("review-draft")
            runCurrent()

            model.resolveGrounded(
                SmsReviewAction.SAVE_DRAFT,
                listOf(
                    SmsFieldCorrection(
                        field = "direction",
                        classification = SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
                        previousRevisionId = null,
                        candidateId = null,
                        evidenceJson = null,
                        newValueJson = "\"debit\""
                    )
                )
            )
            runCurrent()

            assertFalse(model.state.value.actionCompleted)
            assertEquals(refreshed, model.state.value.details)
            coVerify(exactly = 0) { repository.openCases() }
        }

    @Test
    fun `retry validates configuration mode and surfaces pipeline errors safely`() =
        runTest(testDispatcher) {
            val repository = mockk<SmsReviewRepository>(relaxed = true)
            val pipelineService = mockk<PipelineService>()
            val details = mockDetails("review-retry", revision = 3L)
            coEvery { repository.details("review-retry") } returns details
            coEvery { repository.resolve(any(), any()) } returns mockk(relaxed = true)
            coEvery { repository.openCases() } returns emptyList()

            val model = ReviewViewModel(repository, pipelineService)
            model.loadDetails("review-retry")
            runCurrent()

            model.resolve(SmsReviewAction.RETRY, retryConfiguration = "invalid")
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { repository.resolve(any(), any()) }

            coEvery {
                pipelineService.retryReview("review-retry", "current")
            } returns PipelineService.ProcessingResult.AwaitingConfiguration
            model.resolve(SmsReviewAction.RETRY, retryConfiguration = "current")
            runCurrent()
            assertNotNull(model.state.value.error)
            assertFalse(model.state.value.actionCompleted)

            coEvery {
                pipelineService.retryReview("review-retry", "original")
            } returns PipelineService.ProcessingResult.Skipped(
                PipelineService.SkipReason.RETAINED_FOR_REVIEW
            )
            model.resolve(SmsReviewAction.RETRY, retryConfiguration = "original")
            runCurrent()
            assertNull(model.state.value.error)
            assertTrue(model.state.value.actionCompleted)
            coVerify(exactly = 1) { repository.openCases() }
        }

    @Test
    fun `resolveGrounded forwards command for confirm and rejects unsupported actions`() =
        runTest(testDispatcher) {
            val repository = mockk<SmsReviewRepository>(relaxed = true)
            val details = mockDetails("review-confirm", revision = 4L)
            coEvery { repository.details("review-confirm") } returns details
            coEvery { repository.openCases() } returns emptyList()
            val commandSlot = slot<SmsReviewCommand>()
            coEvery { repository.resolve(capture(commandSlot), any()) } returns mockk(relaxed = true)

            val model = ReviewViewModel(repository, mockk())
            model.loadDetails("review-confirm")
            runCurrent()

            model.resolveGrounded(SmsReviewAction.RETRY, emptyList())
            assertNotNull(model.state.value.error)
            coVerify(exactly = 0) { repository.resolve(any(), any()) }

            model.resolveGrounded(SmsReviewAction.CONFIRM, emptyList())
            runCurrent()

            assertTrue(model.state.value.actionCompleted)
            assertNull(model.state.value.error)
            assertEquals("review-confirm", commandSlot.captured.reviewCaseId)
            assertEquals(4L, commandSlot.captured.expectedRevision)
            assertEquals(SmsReviewAction.CONFIRM, commandSlot.captured.action)
            coVerify(exactly = 1) { repository.openCases() }
        }

    private fun mockDetails(id: String, revision: Long): SmsReviewDetails {
        val details = mockk<SmsReviewDetails>(relaxed = true)
        every { details.reviewCase.id } returns id
        every { details.reviewCase.revision } returns revision
        return details
    }
}
