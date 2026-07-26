package com.xtv.app.core.budget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import com.xtv.app.core.budget.UsageApi
import java.time.YearMonth

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
 * What is recorded here is an **estimate from response sizes**, not a bill. The authoritative number
 * lives in the developer console, and the UI should say so rather than implying otherwise.
 */
class SpendGuard(private val context: Context) {

    /** Per-post price for non-owned reads, which is what the home timeline is. */
    private val pricePerPost = 0.005

    suspend fun state(): State {
        val prefs = context.budgetStore.data.first()
        val month = prefs[MONTH]
        val posts = if (month == currentMonth()) prefs[POSTS] ?: 0 else 0
        val cap = prefs[CAP_CENTS]?.let { it / 100.0 } ?: DEFAULT_CAP_USD
        return State(postsThisMonth = posts, spentUsd = posts * pricePerPost, capUsd = cap)
    }

    /**
     * How many posts may be read right now.
     *
     * Returns a number that is never larger than [want] and never takes the month past its ceiling —
     * a request is trimmed rather than refused, so a nearly-exhausted budget still yields a short
     * reel instead of nothing.
     */
    suspend fun allowance(want: Int): Int {
        val state = state()
        val remainingUsd = (state.capUsd - state.spentUsd).coerceAtLeast(0.0)
        val affordable = (remainingUsd / pricePerPost).toInt()
        return want.coerceAtMost(affordable)
    }

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

    data class State(
        val postsThisMonth: Int,
        val spentUsd: Double,
        val capUsd: Double,
        /** True when [postsThisMonth] came from X's own usage meter rather than the local tally. */
        val authoritative: Boolean = false,
    ) {
        val exceeded: Boolean get() = spentUsd >= capUsd
        /** Just the money. The limit only appears when it has actually been hit. */
        val spentText: String get() = "$%.2f".format(spentUsd)
        val capText: String get() = "$%.2f".format(capUsd)
    }

    /**
     * Replaces the local tally with X's own count when an app-only bearer is configured.
     *
     * The local number is an upper bound (X dedupes repeat reads of a resource within a UTC day), so
     * where the two disagree, X's is the one to show.
     */
    suspend fun stateWithUsage(appOnlyBearer: String?): State {
        val local = state()
        val bearer = appOnlyBearer?.takeIf { it.isNotBlank() } ?: return local
        val posts = UsageApi.postsThisMonth(bearer) ?: return local
        return local.copy(postsThisMonth = posts, spentUsd = posts * pricePerPost, authoritative = true)
    }

    private fun currentMonth(): String = YearMonth.now().toString()

    private companion object {
        /** Measured usage is ~$9/month; this is a tripwire, not a target. */
        const val DEFAULT_CAP_USD = 20.0
        val MONTH = stringPreferencesKey("month")
        val POSTS = intPreferencesKey("posts")
        val CAP_CENTS = intPreferencesKey("cap_cents")
    }
}
