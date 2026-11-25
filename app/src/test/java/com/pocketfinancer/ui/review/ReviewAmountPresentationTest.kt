package com.pocketfinancer.ui.review

import kotlin.test.assertEquals
import org.junit.Test

class ReviewAmountPresentationTest {
    @Test
    fun minorUnitsAreDisplayedAsMajorCurrencyUnits() {
        assertEquals("Amount: INR 125.00", formatReviewAmount(12_500, "INR"))
        assertEquals("Amount: INR 0.01", formatReviewAmount(1, "INR"))
        assertEquals("Amount: Unassigned", formatReviewAmount(null, "INR"))
    }
}
