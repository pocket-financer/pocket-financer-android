package com.pocketfinancer.ui.home

import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.ui.onboarding.HistoricalImportCounters
import com.pocketfinancer.ui.onboarding.recordSkipped
import org.junit.Assert.*
import org.junit.Test

class RetainedReviewImportPresentationTest {
    @Test fun retainedAndRejectedAlertsHaveSeparateDurableCounts() {
        val counters = HistoricalImportCounters()
            .recordSkipped(PipelineService.SkipReason.RETAINED_FOR_REVIEW)
            .recordSkipped(PipelineService.SkipReason.NOT_TRANSACTION)
        assertEquals(2, counters.processedCount)
        assertEquals(1, counters.retainedReviewCount)
        assertEquals(1, counters.rejectedCount)
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(fake.preferences, hasSmsPermissions = true)
        store.update { it.copy(processedCount = counters.processedCount,
            retainedReviewCount = counters.retainedReviewCount, rejectedCount = counters.rejectedCount) }
        val reopened = SetupImportStore(fake.preferences, hasSmsPermissions = true).state.value
        assertEquals(1, reopened.retainedReviewCount)
        assertEquals(1, reopened.rejectedCount)
    }

    @Test fun completedReviewImportOffersReviewInsteadOfAnotherScan() {
        listOf(SetupImportStatus.READY, SetupImportStatus.READY_NO_HISTORY).forEach { status ->
            val card = setupImportCardModel(SetupImportState(status = status,
                retainedReviewCount = 3, modelPrepared = true))
            assertEquals("Alerts saved for your review", card.title)
            assertEquals(SetupCardAction.OPEN_REVIEWS, card.primaryAction)
            assertTrue(card.body.contains("3 alerts"))
            assertFalse(card.body.contains("No transactions were added automatically"))
        }
        assertEquals(SetupCardActionTarget.OPEN_REVIEWS, SetupCardAction.OPEN_REVIEWS.target())
    }

    @Test fun oldCountsDoNotInventReviewTotals() {
        val card = setupImportCardModel(SetupImportState(status = SetupImportStatus.READY_NO_HISTORY,
            emptyReason = SetupEmptyReason.CANDIDATES_REJECTED, rejectedCount = 14, modelPrepared = true))
        assertEquals("History checked; no transactions added", card.title)
        assertEquals(SetupCardAction.OPEN_REVIEWS, card.primaryAction)
        assertFalse(card.body.contains("14 alerts"))
    }
}
