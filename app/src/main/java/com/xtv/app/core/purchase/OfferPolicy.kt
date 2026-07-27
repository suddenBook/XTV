package com.xtv.app.core.purchase

import java.security.MessageDigest

/**
 * The three sizes XTV sells, and their prices.
 *
 * There is deliberately no arithmetic here beyond the rate card. An earlier version sized the offers
 * against a local monthly allowance — trimming a hundred posts down to forty-five as the month wore
 * on, dropping any offer whose expected yield rounded to zero videos, and marking the rest for a
 * confirmation dialog. The money is the operator's own and the Developer Console's hard limit is the
 * only thing that can actually stop a charge, so all of that was an app second-guessing a decision
 * that was never its to make.
 */
class OfferPolicy(private val rateCard: RateCard) {

    fun createOffers(record: PurchaseRecord, revision: Long): List<ReelOffer> {
        // A paid request already holds the slot, or provisioning does. Either way this is not the
        // moment to offer another one.
        if (record.pending != null || record.provisioningInFlight) return emptyList()

        return SIZES.mapIndexed { index, posts ->
            val quote = rateCard.quote(posts, identityLookupNeeded = record.accountId == null)
            ReelOffer(
                token = tokenFor(record, revision, posts),
                id = OfferId.entries[index],
                requestedPosts = posts,
                estimatedVideos = quote.expectedResources.media,
                charge = quote,
                accountScope = record.accountScope,
                projectScope = record.projectScope,
            )
        }
    }

    /**
     * Binds an offer to the state that priced it.
     *
     * Re-checked in the PREPARED transaction, so a card left on screen across a reprovision or a
     * rate-card correction cannot be redeemed at its old price.
     */
    private fun tokenFor(
        record: PurchaseRecord,
        revision: Long,
        requestedPosts: Int,
    ): OfferToken {
        val bound = listOf(
            revision,
            record.accountScope.orEmpty(),
            record.projectScope.orEmpty(),
            requestedPosts,
            rateCard.version,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(bound.toByteArray())
        return OfferToken(digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        /** Short, Standard, Long — positional, and always all three. */
        val SIZES = listOf(30, 60, RateCard.MAX_POSTS_PER_REQUEST)
    }
}
