package com.pocketfinancer.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketfinancer.data.repository.SmsReviewGrounding
import com.pocketfinancer.data.repository.SmsReviewSourceSpan
import org.junit.Assert.assertEquals
import com.pocketfinancer.ui.theme.PocketFinancerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceSelectionComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceRemainsReadOnlyAndSelectedTextCanBeAssigned() {
        val source = "🔔 INR 10 debited from XX1234"
        val amount = SmsReviewGrounding.span(source, 2, 8)
        val account = SmsReviewGrounding.span(source, 22, 28)
        var selected: SmsReviewSourceSpan? = null

        composeRule.setContent {
            PocketFinancerTheme {
                EvidenceSelectionText(
                    source = source,
                    selections = mapOf(
                        ReviewField.AMOUNT to amount,
                        ReviewField.ACCOUNT to account
                    ),
                    pendingSelection = account,
                    onSelectionChanged = { selected = it }
                )
            }
        }

        composeRule.onNodeWithText(source).assertExists()
        composeRule.onNodeWithContentDescription(
            "SMS evidence. Select source text, then tap the field it belongs to."
        )
            .assertExists()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetSelection))
        composeRule.onNodeWithContentDescription(
            "SMS evidence. Select source text, then tap the field it belongs to."
        ).performSemanticsAction(SemanticsActions.SetSelection) { it(3, 9, false) }
        composeRule.runOnIdle { assertEquals("INR 10", selected?.text) }
    }

    @Test
    fun selectedTextSurvivesTappingTheFieldChip() {
        val source = "INR 125.00 debited from A/c XX1234"
        var assigned: SmsReviewSourceSpan? = null

        composeRule.setContent {
            PocketFinancerTheme {
                var pending by remember { mutableStateOf<SmsReviewSourceSpan?>(null) }
                Column {
                    AssistChip(
                        onClick = { assigned = pending },
                        label = { Text("Amount") }
                    )
                    EvidenceSelectionText(
                        source = source,
                        selections = emptyMap(),
                        pendingSelection = pending,
                        onSelectionChanged = { pending = it }
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(
            "SMS evidence. Select source text, then tap the field it belongs to."
        ).performSemanticsAction(SemanticsActions.SetSelection) { it(0, 10, false) }
        composeRule.onNodeWithText("Amount").performClick()
        composeRule.runOnIdle { assertEquals("INR 125.00", assigned?.text) }
    }
}
