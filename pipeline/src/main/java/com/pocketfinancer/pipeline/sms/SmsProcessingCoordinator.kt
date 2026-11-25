package com.pocketfinancer.pipeline.sms

interface SmsProcessingCoordinator {
    suspend fun process(
        source: AdmittedMessageRef,
        operation: SmsOperationSnapshot,
        observer: SmsProcessingObserver = SmsProcessingObserver.None
    ): SmsProcessingOutcome

    suspend fun requestStop(operationId: String): SmsProcessingStopReceipt
}

data class SmsProcessingStopReceipt(
    val operationId: String,
    val reviewCaseId: String?,
    val state: String,
    val committed: Boolean
)
