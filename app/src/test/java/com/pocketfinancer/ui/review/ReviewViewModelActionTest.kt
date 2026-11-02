package com.pocketfinancer.ui.review

import com.pocketfinancer.data.repository.SmsReviewAction
import com.pocketfinancer.data.repository.SmsReviewDetails
import com.pocketfinancer.data.repository.SmsReviewRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelActionTest {
    @Test fun repeatedActionsCannotRaceAnInFlightSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = mockk<SmsReviewRepository>(relaxed = true)
            val details = mockk<SmsReviewDetails>(relaxed = true)
            every { details.reviewCase.id } returns "review"
            every { details.reviewCase.revision } returns 0L
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
            model.resolve(SmsReviewAction.REJECT)
            runCurrent()
            coVerify(exactly = 1) { repository.resolve(any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
