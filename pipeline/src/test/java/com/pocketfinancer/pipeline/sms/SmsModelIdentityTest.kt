package com.pocketfinancer.pipeline.sms

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SmsModelIdentityTest {
    @Test
    fun eligibleFileBackedModelRequiresRealLowercaseSha() {
        assertFailsWith<IllegalArgumentException> { SmsModelIdentity(true, "model", null) }
        assertFailsWith<IllegalArgumentException> {
            SmsModelIdentity(true, "model", "a".repeat(63))
        }
        SmsModelIdentity(true, "model", "a".repeat(64))
        SmsModelIdentity(false, null, null)
    }
}
