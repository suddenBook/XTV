package com.xtv.app.core.budget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.legacyBudgetStore by preferencesDataStore("xtv_budget")

/**
 * The pre-v2 plaintext spending ledger, which now only needs erasing.
 *
 * It used to be reconstructed into the current ledger by multiplying the stored Post count by a
 * conservative per-Post reservation. That reservation was 35 000 micro-USD, fixed before the rate
 * model was corrected against a real Console statement — nearly six times the 6 000 a Post actually
 * reserves — so migrating a modestly used v1 install produced a fabricated debt large enough to trip
 * the monthly guard on first launch. With no guard to trip there is nothing left to reconstruct:
 * X's own project usage is the figure XTV shows, and these plaintext preferences are simply stale.
 *
 * [hasState] still has to answer honestly. Clearing only ever happens on a migration pass that a
 * non-empty read triggers, so reporting "nothing here" would leave the file on disk forever.
 */
internal class LegacyBudgetStateReader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun hasState(): Boolean =
        appContext.legacyBudgetStore.data.first().asMap().isNotEmpty()

    suspend fun clear() {
        appContext.legacyBudgetStore.edit { it.clear() }
    }
}
