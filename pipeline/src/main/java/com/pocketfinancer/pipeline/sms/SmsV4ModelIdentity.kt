package com.pocketfinancer.pipeline.sms

/** V4's file-backed identity rule; never substitutes file metadata for a SHA-256. */
data class SmsV4ModelIdentity(
    val eligible: Boolean,
    val modelIdentifier: String?,
    val modelFileSha256: String?
) {
    val kind: String = "file_sha256"

    init {
        if (eligible) {
            require(!modelIdentifier.isNullOrBlank())
            require(modelFileSha256?.matches(Regex("[0-9a-f]{64}")) == true)
        } else {
            require(modelIdentifier == null || modelIdentifier.isNotBlank())
            require(modelFileSha256 == null || modelFileSha256.matches(Regex("[0-9a-f]{64}")))
        }
    }
}
