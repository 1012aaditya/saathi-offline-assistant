package app.saathi.braille

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * Waits for ONE one-time-code SMS using Google's SMS User Consent API:
 * - no SMS permission; the app can't read the inbox;
 * - when an SMS with a code arrives (within 5 minutes), Android itself asks
 *   "Allow Saathi to read this message?"; only after Allow does that one message reach us;
 * - we keep only the extracted code, in memory, and drop the message text immediately.
 *
 * Never log, speak, store or send the code anywhere (CLAUDE.md rule 2, braille exception).
 */
@Stable
class OtpWaiter internal constructor() {
    enum class State { Idle, Waiting, Asking, TimedOut, Declined, NoCode, Unavailable }

    var state by mutableStateOf(State.Idle)
        internal set
    internal var start: () -> Unit = {}
    internal var stop: () -> Unit = {}

    fun begin() = start()
    fun cancel() = stop()
}

private const val GMS = "com.google.android.gms"

@Composable
fun rememberOtpWaiter(onCode: (String) -> Unit): OtpWaiter {
    val context = LocalContext.current
    val gotCode by rememberUpdatedState(onCode)
    val waiter = remember { OtpWaiter() }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            waiter.state = OtpWaiter.State.Declined
            return@rememberLauncherForActivityResult
        }
        // The full message exists only in this local; it is never stored or passed on.
        val code = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)?.let(::extractOtp)
        if (code == null) {
            waiter.state = OtpWaiter.State.NoCode
        } else {
            waiter.state = OtpWaiter.State.Idle
            gotCode(code)
        }
    }

    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION || waiter.state != OtpWaiter.State.Waiting) return
                val extras = intent.extras ?: return
                val status = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_STATUS, Status::class.java) ?: return
                when (status.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        val ask = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                        // Only launch Google Play services' own consent screen, and never pass URI grants
                        // (guards against a spoofed broadcast redirecting us to another activity).
                        val target = ask?.resolveActivity(ctx.packageManager)
                        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        if (ask == null || target?.packageName != GMS || ask.flags and grants != 0) {
                            waiter.state = OtpWaiter.State.Unavailable
                            return
                        }
                        waiter.state = OtpWaiter.State.Asking
                        consent.launch(ask)
                    }
                    CommonStatusCodes.TIMEOUT -> waiter.state = OtpWaiter.State.TimedOut
                }
            }
        }
    }

    waiter.start = {
        SmsRetriever.getClient(context).startSmsUserConsent(null)
            .addOnSuccessListener { waiter.state = OtpWaiter.State.Waiting }
            .addOnFailureListener { waiter.state = OtpWaiter.State.Unavailable }
    }
    waiter.stop = { if (waiter.state == OtpWaiter.State.Waiting) waiter.state = OtpWaiter.State.Idle }

    DisposableEffect(Unit) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, // only Google Play services can send this broadcast
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return waiter
}
