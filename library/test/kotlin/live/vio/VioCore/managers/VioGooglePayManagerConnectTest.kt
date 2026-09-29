package live.vio.VioCore.managers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Stripe Connect (ADR-0022): a Google Pay token for a Connect seller must be
 * created on the seller's connected account. Stripe's own GooglePayConfig
 * encodes it as "<publishableKey>/<acct_…>"; without an account the key goes
 * alone, exactly as before.
 */
class VioGooglePayManagerConnectTest {

    private fun keyOf(stripeAccount: String?): String =
        VioGooglePayManager.createPaymentDataRequest(
            gateway = "stripe",
            gatewayMerchantId = "pk_test_platform",
            price = "10.00",
            currency = "NOK",
            stripeAccount = stripeAccount,
        ).getJSONArray("allowedPaymentMethods").getJSONObject(0)
            .getJSONObject("tokenizationSpecification")
            .getJSONObject("parameters")
            .getString("stripe:publishableKey")

    @Test
    fun `without Connect the publishable key goes alone`() {
        assertEquals("pk_test_platform", keyOf(null))
    }

    @Test
    fun `Connect names the seller account the Stripe way`() {
        assertEquals("pk_test_platform/acct_1Seller", keyOf("acct_1Seller"))
    }

    @Test
    fun `something that is not an account id is ignored`() {
        assertEquals("pk_test_platform", keyOf("cus_123"))
    }
}
