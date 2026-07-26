package com.xtv.app.core.budget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.first

private val Context.budgetStore by preferencesDataStore("xtv_budget")
private const val TAG = "XTV-BUDGET"

/**
 * Counts what the app spends and refuses to spend past a ceiling.
 *
 * X bills per **post read**, not per usable item, so the only quantity that costs money is how many
 * posts a fetch pulls — which makes the budget a number the app fully controls. A measured reel runs
 * about $0.0084 per video at this timeline's ~60% video share, so a nightly habit lands near
 * $9/month; the default ceiling is set well above that so it only ever catches something going
 * wrong.
 *
 * Two numbers live here and they are **not** interchangeable — see [State].
 */
class SpendGuard(private val context: Context) {

    suspend fun state(): State {
        val prefs = context.budgetStore.data.first()
        val month = prefs[MONTH]
        val posts = if (month == currentMonth()) prefs[POSTS] ?: 0 else 0
        val cap = prefs[CAP_CENTS]?.let { it / 100.0 } ?: DEFAULT_CAP_USD
        return State(postsThisMonth = posts, spentUsd = posts * PRICE_PER_POST, capUsd = cap)
    }

    /**
     * How many posts may be read right now.
     *
     * Returns a number that is never larger than [want] and never takes the month past its ceiling —
     * a request is trimmed rather than refused, so a nearly-exhausted budget still yields a short
     * reel instead of nothing.
     */
    suspend fun allowance(want: Int): Int = state().let { affordable(it.capUsd, it.spentUsd, want) }

    /** Record posts actually read. Call with what came back, not what was asked for. */
    suspend fun record(postsRead: Int) {
        if (postsRead <= 0) return
        context.budgetStore.edit { prefs ->
            val month = currentMonth()
            val previous = if (prefs[MONTH] == month) prefs[POSTS] ?: 0 else 0
            prefs[MONTH] = month
            prefs[POSTS] = previous + postsRead
        }
        val now = state()
        Log.i(TAG, "recorded $postsRead posts; month total ${now.postsThisMonth} = ${now.spentText}")
    }

    suspend fun setCap(usd: Double) {
        context.budgetStore.edit { it[CAP_CENTS] = (usd * 100).toInt() }
    }

    /**
     * What the home screen shows, and what the ceiling is enforced against.
     *
     * These are deliberately separate fields because they count **different windows**:
     *
     *  - [spentUsd] is this app's own tally over a calendar month, rolling over on the 1st. It is an
     *    upper bound (X dedupes repeat reads within a UTC day) and it is the only figure the local
     *    ceiling may be compared against.
     *  - [periodSpentUsd] is X's own count over X's own period, which ends on `cap_reset_day`. It is
     *    *project*-wide, so it includes any other script sharing the credentials.
     *
     * Enforcing the ceiling against X's figure was the earlier behaviour and it is a trap: a
     * `xurl` session on the same project could push the number past $20 and disable every reel card
     * in an app that had spent cents. [exceeded] therefore reads the local tally — the thing the $20
     * tripwire actually governs — while the display prefers X's number, which is the true bill.
     */
    data class State(
        val postsThisMonth: Int,
        val spentUsd: Double,
        val capUsd: Double,
        val periodSpentUsd: Double? = null,
        /** Day of month X's period rolls over on. Non-null only when [authoritative] is. */
        val resetDay: Int? = null,
    ) {
        val authoritative: Boolean get() = periodSpentUsd != null
        val exceeded: Boolean get() = spentUsd >= capUsd
        /** Just the money. The limit only appears when it has actually been hit. */
        val spentText: String get() = usd(periodSpentUsd ?: spentUsd)
        val capText: String get() = usd(capUsd)

        /**
         * Fold in X's meter, if it answered.
         *
         * [UsageApi.Usage.capPosts] is intentionally discarded: X's cap is 2,000,000 posts, about
         * $10,000, which is not a budget guard by any reading. The $20 local ceiling is the actual
         * safety feature and it stays in charge.
         */
        fun mergedWith(usage: UsageApi.Usage?): State = when (usage) {
            null -> this
            else -> copy(periodSpentUsd = usage.posts * PRICE_PER_POST, resetDay = usage.resetDay)
        }
    }

    private fun currentMonth(): String = YearMonth.now().toString()

    companion object {
        /** Per-post price for non-owned reads, which is what the home timeline is. */
        const val PRICE_PER_POST = 0.005

        /** Measured usage is ~$9/month; this is a tripwire, not a target. */
        const val DEFAULT_CAP_USD = 20.0

        /**
         * How many posts fit in what is left of the ceiling, never more than [want].
         *
         * Pure, because it is the money maths and it should be checkable without a device.
         */
        fun affordable(
            capUsd: Double,
            spentUsd: Double,
            want: Int,
            pricePerPost: Double = PRICE_PER_POST,
        ): Int {
            val remainingUsd = (capUsd - spentUsd).coerceAtLeast(0.0)
            return want.coerceAtMost((remainingUsd / pricePerPost).toInt())
        }

        /** Always US formatting: it is a dollar figure, not a localisable quantity. */
        fun usd(amount: Double): String = String.format(Locale.US, "$%.2f", amount)

        private val MONTH = stringPreferencesKey("month")
        private val POSTS = intPreferencesKey("posts")
        private val CAP_CENTS = intPreferencesKey("cap_cents")
    }
}
