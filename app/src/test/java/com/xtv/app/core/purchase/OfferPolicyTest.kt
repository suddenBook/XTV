package com.xtv.app.core.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferPolicyTest {

    private val rateCard = RateCard.current()
    private val policy = OfferPolicy(rateCard)

    @Test
    fun `the same three offers are made every time`() {
        val offers = policy.createOffers(PurchaseRecord(accountId = "cached"), revision = 7)

        assertEquals(listOf(30, 60, 100), offers.map { it.requestedPosts })
        assertEquals(listOf(OfferId.SHORT, OfferId.STANDARD, OfferId.LONG), offers.map { it.id })
        assertTrue(offers.all { it.estimatedVideos > 0 })
    }

    @Test
    fun `every offer is priced as a range with the reservation on top`() {
        val offers = policy.createOffers(PurchaseRecord(accountId = "cached"), revision = 8)

        assertTrue(offers.all { it.charge.knownEstimate.value > 0 })
        assertTrue(offers.all { it.charge.reservation >= it.charge.knownEstimate })
        assertTrue(offers.all { it.charge.rateCardVersion == rateCard.version })
    }

    @Test
    fun `a hundred posts is fifty cents, not the pre-correction one eighty`() {
        val longest = policy.createOffers(PurchaseRecord(accountId = "cached"), revision = 9).last()

        assertEquals(100, longest.requestedPosts)
        assertEquals("$0.50", longest.charge.knownEstimate.formatUsd())
    }

    @Test
    fun `an in-flight paid request withdraws the offers`() {
        val record = PurchaseRecord(
            accountId = "cached",
            pending = DurablePurchase(
                id = OperationId("in-flight"),
                token = OfferToken("held"),
                requestedPosts = 30,
                quote = rateCard.quote(30, identityLookupNeeded = false),
                stage = DurableStage.TIMELINE_DISPATCHED,
                startedAtMs = 1,
            ),
        )

        assertTrue(policy.createOffers(record, revision = 10).isEmpty())
    }

    @Test
    fun `provisioning withdraws the offers`() {
        val record = PurchaseRecord(accountId = "cached", provisioningInFlight = true)

        assertTrue(policy.createOffers(record, revision = 11).isEmpty())
    }

    @Test
    fun `an uncached identity is priced into every offer`() {
        val uncached = policy.createOffers(PurchaseRecord(), revision = 12)
        val cached = policy.createOffers(PurchaseRecord(accountId = "cached"), revision = 12)

        uncached.zip(cached).forEach { (needsLookup, known) ->
            assertEquals(
                rateCard.userRead,
                needsLookup.charge.reservation - known.charge.reservation,
            )
        }
    }

    @Test
    fun `a token is bound to the identity, project and price that produced it`() {
        val base = PurchaseRecord(
            accountId = "cached",
            accountScope = "account",
            projectScope = "project",
        )

        val token = policy.createOffers(base, revision = 13).first().token

        assertEquals(token, policy.createOffers(base, revision = 13).first().token)
        assertNotEquals(token, policy.createOffers(base, revision = 14).first().token)
        assertNotEquals(
            token,
            policy.createOffers(base.copy(accountScope = "other"), revision = 13).first().token,
        )
        assertNotEquals(
            token,
            policy.createOffers(base.copy(projectScope = "other"), revision = 13).first().token,
        )
        assertNotEquals(
            token,
            OfferPolicy(rateCard.copy(version = "later-card"))
                .createOffers(base, revision = 13).first().token,
        )
    }

    @Test
    fun `quote includes account lookup when identity is not cached`() {
        val withoutIdentity = rateCard.quote(requestedPosts = 30, identityLookupNeeded = false)
        val withIdentity = rateCard.quote(requestedPosts = 30, identityLookupNeeded = true)

        assertEquals(rateCard.userRead, withIdentity.reservation - withoutIdentity.reservation)
    }
}
