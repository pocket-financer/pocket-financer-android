package com.pocketfinancer.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.pocketfinancer.data.repository.SmsReviewGrounding
import com.pocketfinancer.data.repository.SmsReviewSourceSpan

enum class ReviewField(val label: String) {
    AMOUNT("Amount"),
    DIRECTION("Direction"),
    ACCOUNT("Account"),
    COUNTERPARTY("Counterparty")
}

internal val ReviewHighlightTextColor = Color(0xFF1B1B1F)

@Composable
fun EvidenceSelectionText(
    source: String,
    selections: Map<ReviewField, SmsReviewSourceSpan?>,
    pendingSelection: SmsReviewSourceSpan?,
    onSelectionChanged: (SmsReviewSourceSpan?) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = remember(source, selections) {
        AnnotatedString.Builder(source).apply {
            selections.forEach { (field, span) ->
                span ?: return@forEach
                val range = SmsReviewGrounding.utf16Range(source, span) ?: return@forEach
                addStyle(
                    SpanStyle(
                        background = field.highlightColor(),
                        fontWeight = FontWeight.SemiBold
                    ),
                    range.first,
                    range.last + 1
                )
            }
        }.toAnnotatedString()
    }
    val pending = pendingSelection?.let {
        SmsReviewGrounding.utf16Range(source, it)
    }
    val selection = pending?.let { TextRange(it.first, it.last + 1) } ?: TextRange.Zero
    BasicTextField(
        value = TextFieldValue(annotated, selection),
        onValueChange = { next ->
            if (next.text != source) return@BasicTextField
            if (next.selection.collapsed) {
                onSelectionChanged(null)
                return@BasicTextField
            }
            val scalarRange = SmsReviewGrounding.scalarRange(
                source,
                next.selection.min,
                next.selection.max
            ) ?: return@BasicTextField
            runCatching {
                SmsReviewGrounding.span(source, scalarRange.first, scalarRange.last + 1)
            }.getOrNull()?.let(onSelectionChanged)
        },
        readOnly = true,
        textStyle = TextStyle(color = ReviewHighlightTextColor),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "SMS evidence. Select source text, then tap the field it belongs to."
            }
            .border(1.dp, Color(0xFFCAC4D0), RoundedCornerShape(12.dp))
            .background(Color(0xFFFFFBFE), RoundedCornerShape(12.dp))
            .padding(16.dp),
        decorationBox = { inner -> Box { inner() } }
    )
}

internal fun ReviewField.highlightColor(): Color = when (this) {
    ReviewField.AMOUNT -> Color(0xFFFFD8A8)
    ReviewField.DIRECTION -> Color(0xFFCDE7FF)
    ReviewField.ACCOUNT -> Color(0xFFD7F5D0)
    ReviewField.COUNTERPARTY -> Color(0xFFE9D5FF)
}
