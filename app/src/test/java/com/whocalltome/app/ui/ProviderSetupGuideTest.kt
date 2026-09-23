package com.whocalltome.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSetupGuideTest {
    @Test
    fun everyProviderHasInstructionsAndOfficialHttpsLinks() {
        listOf("ipqs", "tellows", "phoneblock").forEach { provider ->
            val guide = providerSetupGuide(provider)

            assertTrue(guide.accessSummary.isNotBlank())
            assertTrue(guide.steps.isNotEmpty())
            assertTrue(guide.links.isNotEmpty())
            assertTrue(guide.links.all { it.url.startsWith("https://") })
        }
    }

    @Test
    fun guidesUseTheSecretTermsExpectedByEachProvider() {
        val ipqs = providerSetupGuide("ipqs")
        val tellows = providerSetupGuide("tellows")
        val phoneBlock = providerSetupGuide("phoneblock")

        assertEquals("API key", ipqs.secretName)
        assertEquals("apikey", tellows.secretName)
        assertEquals("API token", phoneBlock.secretName)
        assertTrue(ipqs.steps.any { it.contains("API key") })
        assertTrue(tellows.steps.any { it.contains("apikey") })
        assertTrue(phoneBlock.steps.any { it.contains("API token") })
    }

    @Test
    fun guideLinksPointToTheExpectedOfficialPages() {
        assertEquals(
            listOf(
                "https://www.ipqualityscore.com/create-account/phone-validation",
                "https://www.ipqualityscore.com/user/api-keys",
            ),
            providerSetupGuide("ipqs").links.map { it.url },
        )
        assertEquals(
            "https://shop.tellows.de/en/tellows-api-key.html",
            providerSetupGuide("tellows").links.single().url,
        )
        assertEquals(
            "https://phoneblock.net/phoneblock/settings",
            providerSetupGuide("phoneblock").links.single().url,
        )
    }
}
