package com.pocketfinancer.pipeline.sms

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SmsV4ModelIdentityTest {
    @Test fun eligibleFileBackedModelRequiresRealLowercaseSha() {
        assertFailsWith<IllegalArgumentException> { SmsV4ModelIdentity(true, "model", null) }
        assertFailsWith<IllegalArgumentException> { SmsV4ModelIdentity(true, "model", "a".repeat(63)) }
        SmsV4ModelIdentity(true, "model", "a".repeat(64))
        SmsV4ModelIdentity(false, null, null)
    }
}
