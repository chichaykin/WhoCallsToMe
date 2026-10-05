package com.whocalltome.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerIdentityPolicyTest {
    @Test
    fun externalSpamNeverBlocksWithoutPersonalRule() {
        val externalSpam = CallerIdentity(
            e164 = "+6561234567",
            displayName = "Possible spam",
            category = CallerCategory.SPAM,
            source = "phoneblock",
            spamScore = 100,
            isSpam = true,
        )

        assertFalse(externalSpam.shouldBlock)
    }

    @Test
    fun personalBlockAlwaysBlocks() {
        val personallyBlocked = CallerIdentity(
            e164 = "+6561234567",
            displayName = null,
            category = CallerCategory.UNKNOWN,
            source = "personal",
            personalAction = PersonalAction.BLOCK,
        )

        assertTrue(personallyBlocked.shouldBlock)
    }

}
