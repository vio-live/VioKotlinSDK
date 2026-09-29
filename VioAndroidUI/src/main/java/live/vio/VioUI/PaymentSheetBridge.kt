package live.vio.VioUI

import androidx.activity.ComponentActivity
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult

object PaymentSheetBridge {
    private var attachedActivity: ComponentActivity? = null
    private var currentPublishableKey: String? = null
    private var currentStripeAccount: String? = null
    private var paymentSheet: PaymentSheet? = null

    /** Assignado por el caller justo antes de presentar PaymentSheet */
    var onResult: (PaymentSheetResult) -> Unit = {}

    fun attach(activity: ComponentActivity) {
        if (attachedActivity === activity && paymentSheet != null) return
        attachedActivity = activity
        paymentSheet = PaymentSheet(activity) { result -> onResult(result) }
    }

    /**
     * Stripe Connect (ADR-0022): a seller on Connect charges on their
     * connected account, and PaymentSheet can only confirm the intent by
     * naming it. Without an account this is exactly the configuration of old.
     */
    fun ensureConfigured(publishableKey: String, stripeAccount: String? = null) {
        val context = attachedActivity?.applicationContext ?: return
        val account = stripeAccount?.takeIf { it.startsWith("acct_") }
        if (currentPublishableKey != publishableKey || currentStripeAccount != account) {
            if (account != null) {
                PaymentConfiguration.init(context, publishableKey, account)
            } else {
                PaymentConfiguration.init(context, publishableKey)
            }
            currentPublishableKey = publishableKey
            currentStripeAccount = account
        }
    }

    fun presentPaymentIntent(clientSecret: String, configuration: PaymentSheet.Configuration) {
        val sheet = paymentSheet
            ?: throw IllegalStateException("PaymentSheetBridge.attach must be called before presenting")
        sheet.presentWithPaymentIntent(clientSecret, configuration)
    }

    fun isReady(): Boolean = paymentSheet != null
}
