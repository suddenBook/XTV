package com.xtv.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.xtv.app.core.auth.ProvisioningRequest
import com.xtv.app.core.auth.ProvisioningResult
import com.xtv.app.core.purchase.PurchaseCommand
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Executes credential provisioning.
 *
 * The manifest protects this exported component with the platform signature-level DUMP permission:
 * adb's shell identity can provision it directly, while the original [MainActivity] extras workflow
 * forwards here for compatibility. It is not a launcher activity and never sends credentials back
 * to [MainActivity].
 */
class ProvisioningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val request = ProvisioningRequest(
            requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty(),
            clientId = intent.getStringExtra(EXTRA_CLIENT_ID).orEmpty(),
            refreshToken = intent.getStringExtra(EXTRA_REFRESH_TOKEN).orEmpty(),
            appOnlyBearer = intent.getStringExtra(EXTRA_BEARER).orEmpty(),
        )
        // Do not retain or save secrets in the Activity's Intent. dumpsys should see empty extras as
        // soon as onCreate has copied them into process memory.
        intent.replaceExtras(Bundle())
        setIntent(intent)

        val status = TextView(this).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.provisioning_in_progress)
            textSize = 22f
        }
        setContentView(status)

        val graph = (application as XtvApplication).graph
        // The rotation workflow belongs to the process, not this Activity lifecycle. Configuration
        // change/destruction may stop observing it, but cannot cancel after the old token is spent.
        val pendingResult = graph.applicationScope.async {
            val result = graph.provisioning.provision(request)
            // Script-visible completion and local-state release are process-owned terminal effects.
            // They must happen even if this transient Activity is destroyed while awaiting them.
            graph.purchases.dispatch(PurchaseCommand.ReloadLocalState)
            when (result) {
                is ProvisioningResult.Committed -> Log.i(
                    TAG,
                    "request=${sanitizeRequestId(result.requestId)} status=COMMITTED " +
                        "preserve=${result.preservedPrivateState}",
                )
                is ProvisioningResult.Rejected -> Log.w(
                    TAG,
                    "request=${sanitizeRequestId(result.requestId)} status=REJECTED " +
                        "reason=${result.reason}",
                )
            }
            result
        }

        lifecycleScope.launch {
            val result = pendingResult.await()
            status.text = when (result) {
                is ProvisioningResult.Committed -> {
                    getString(R.string.provisioning_complete)
                }
                is ProvisioningResult.Rejected -> {
                    getString(R.string.provisioning_failed, result.reason.name)
                }
            }
            // The shell observes the request-scoped log result. Leaving this panel open would make
            // provisioning look like a new launcher destination, so return to the prior task.
            finish()
        }
    }

    companion object {
        private const val TAG = "XTV-PROVISION"
        internal const val EXTRA_REQUEST_ID = "request_id"
        internal const val EXTRA_CLIENT_ID = "client_id"
        internal const val EXTRA_REFRESH_TOKEN = "refresh_token"
        internal const val EXTRA_BEARER = "bearer"

        internal fun intent(context: Context, request: ProvisioningRequest): Intent =
            Intent(context, ProvisioningActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, request.requestId)
                .putExtra(EXTRA_CLIENT_ID, request.clientId)
                .putExtra(EXTRA_REFRESH_TOKEN, request.refreshToken)
                .putExtra(EXTRA_BEARER, request.appOnlyBearer)

        fun sanitizeRequestId(value: String): String =
            value.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifBlank { "missing" }
    }
}
