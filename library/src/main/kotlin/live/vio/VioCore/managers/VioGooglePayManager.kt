package live.vio.VioCore.managers

import android.app.Activity
import android.content.Intent
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.*
import live.vio.VioCore.utils.VioLogger
import live.vio.VioUI.VioGooglePayActivity
import live.vio.sdk.core.helpers.JsonUtils
import live.vio.sdk.domain.models.VioShippingContact
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager for handling Google Pay integration using the Google Pay API.
 */
object VioGooglePayManager {
    private const val TAG = "VioGooglePayManager"
    
    // Request code for Google Pay activity result
    const val LOAD_PAYMENT_DATA_REQUEST_CODE = 991

    private val baseRequest = JSONObject().apply {
        put("apiVersion", 2)
        put("apiVersionMinor", 0)
    }

    /**
     * Maps Vio SDK environment to Google Pay constants.
     */
    fun getGooglePayEnvironment(vioEnv: live.vio.VioCore.configuration.VioEnvironment): Int {
        return if (vioEnv == live.vio.VioCore.configuration.VioEnvironment.PRODUCTION) {
            WalletConstants.ENVIRONMENT_PRODUCTION
        } else {
            WalletConstants.ENVIRONMENT_TEST
        }
    }

    private fun getBaseCardPaymentMethod(): JSONObject {
        return JSONObject().apply {
            put("type", "CARD")
            put("parameters", JSONObject().apply {
                put("allowedAuthMethods", JSONArray(listOf("PAN_ONLY", "CRYPTOGRAM_3DS")))
                put("allowedCardNetworks", JSONArray(listOf("AMEX", "DISCOVER", "INTERAC", "JCB", "MASTERCARD", "VISA")))
                put("billingAddressRequired", true)
                put("billingAddressParameters", JSONObject().apply {
                    put("format", "FULL")
                })
            })
        }
    }

    private fun getIsReadyToPayRequest(): JSONObject {
        return baseRequest.apply {
            put("allowedPaymentMethods", JSONArray().put(getBaseCardPaymentMethod()))
        }
    }

    /**
     * Check if Google Pay is available for the user.
     * @param environment Google Pay environment (e.g., WalletConstants.ENVIRONMENT_TEST)
     */
    fun isGooglePayAvailable(
        activity: Activity,
        environment: Int = WalletConstants.ENVIRONMENT_TEST,
        callback: (Boolean) -> Unit
    ) {
        val paymentsClient = Wallet.getPaymentsClient(
            activity,
            Wallet.WalletOptions.Builder()
                .setEnvironment(environment)
                .build()
        )
        
        val request = IsReadyToPayRequest.fromJson(getIsReadyToPayRequest().toString())
        paymentsClient.isReadyToPay(request).addOnCompleteListener { task ->
            try {
                callback(task.getResult(ApiException::class.java) == true)
            } catch (exception: ApiException) {
                VioLogger.error("isReadyToPay failed: ${exception.message}", TAG)
                callback(false)
            }
        }
    }

    /**
     * Create a payment data request for Google Pay.
     * @param gateway Vio backend gateway identifier.
     * @param gatewayMerchantId Vio backend merchant identifier.
     * @param price Total price in decimal format (e.g., "10.00").
     * @param currency Currency code (e.g., "USD").
     * @param shippingAddressRequired Whether shipping address is required.
     * @param phoneNumberRequired Whether phone number is required in shipping address.
     */
    fun createPaymentDataRequest(
        gateway: String,
        gatewayMerchantId: String,
        price: String,
        currency: String,
        shippingAddressRequired: Boolean = false,
        phoneNumberRequired: Boolean = false,
        stripeAccount: String? = null
    ): JSONObject {
        val cardPaymentMethod = getBaseCardPaymentMethod().apply {
            put("tokenizationSpecification", JSONObject().apply {
                put("type", "PAYMENT_GATEWAY")
                put("parameters", JSONObject().apply {
                    put("gateway", gateway)
                    if (gateway == "stripe") {
                        put("stripe:version", "2023-10-16")
                        // Stripe Connect (ADR-0022): the token must be created on
                        // the seller's connected account. Same format as Stripe's
                        // own GooglePayConfig: "<publishableKey>/<acct_…>".
                        val account = stripeAccount?.takeIf { it.startsWith("acct_") }
                        put(
                            "stripe:publishableKey",
                            if (account != null) "$gatewayMerchantId/$account" else gatewayMerchantId
                        )
                    } else {
                        put("gatewayMerchantId", gatewayMerchantId)
                    }
                })
            })
        }

        return JSONObject(baseRequest.toString()).apply {
            put("allowedPaymentMethods", JSONArray().put(cardPaymentMethod))
            put("transactionInfo", JSONObject().apply {
                put("totalPrice", price)
                put("totalPriceStatus", "FINAL")
                put("currencyCode", currency)
            })
            put("merchantInfo", JSONObject().apply {
                put("merchantName", "Vio Merchant")
            })
            // Request the email from the user's Google account
            put("emailRequired", true)

            if (shippingAddressRequired) {
                put("shippingAddressRequired", true)
                put("shippingAddressParameters", JSONObject().apply {
                    put("allowedCountryCodes", JSONArray(listOf("US", "GB", "NO", "ES", "IT", "FR", "DE")))
                    put("phoneNumberRequired", phoneNumberRequired)
                })
            }
        }
    }

    /**
     * Launch the Google Pay sheet.
     * @param environment Google Pay environment (e.g., WalletConstants.ENVIRONMENT_TEST)
     */
    fun launchGooglePay(
        activity: Activity,
        paymentDataRequestJson: JSONObject,
        environment: Int = WalletConstants.ENVIRONMENT_TEST
    ) {
        val paymentsClient = Wallet.getPaymentsClient(
            activity,
            Wallet.WalletOptions.Builder()
                .setEnvironment(environment)
                .build()
        )

        val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())
        AutoResolveHelper.resolveTask(
            paymentsClient.loadPaymentData(request),
            activity,
            LOAD_PAYMENT_DATA_REQUEST_CODE
        )
    }

    /**
     * Creates an Intent to launch Google Pay via VioGooglePayActivity.
     * This is a bridge for ActivityResultLauncher<Intent>.
     */
    fun getGooglePayIntent(
        activity: Activity,
        paymentDataRequestJson: JSONObject,
        environment: Int = WalletConstants.ENVIRONMENT_TEST
    ): Intent {
        val intent = Intent(activity, VioGooglePayActivity::class.java)
        intent.putExtra("paymentDataRequestJson", paymentDataRequestJson.toString())
        intent.putExtra("environment", environment)
        return intent
    }

    /**
     * Data class to hold all relevant information from a Google Pay payment result.
     */
    data class VioGooglePayResult(
        val token: String,
        val email: String? = null,
        val shippingContact: VioShippingContact? = null
    )

    /**
     * Handle the result from the Google Pay activity.
     */
    fun handlePaymentSuccess(data: Intent?): String? {
        return extractFullPaymentData(data)?.token
    }

    /**
     * Extracts all relevant data from Google Pay result.
     */
    fun extractFullPaymentData(data: Intent?): VioGooglePayResult? {
        val paymentData = PaymentData.getFromIntent(data ?: return null) ?: return null
        val paymentInformation = paymentData.toJson()
        
        return try {
            val root = JSONObject(paymentInformation)
            val paymentMethodData = root.getJSONObject("paymentMethodData")
            val rawToken = paymentMethodData.getJSONObject("tokenizationData").getString("token")
            val token = extractTokenId(rawToken)
            val email = root.optString("email").takeIf { it.isNotEmpty() }
            
            val shippingContact = if (root.has("shippingAddress")) {
                val sa = root.getJSONObject("shippingAddress")
                VioShippingContact(
                    name = sa.optString("name").takeIf { it.isNotEmpty() },
                    phone = sa.optString("phoneNumber").takeIf { it.isNotEmpty() },
                    address1 = sa.optString("address1").takeIf { it.isNotEmpty() },
                    address2 = sa.optString("address2").takeIf { it.isNotEmpty() },
                    city = sa.optString("locality").takeIf { it.isNotEmpty() },
                    administrativeArea = sa.optString("administrativeArea").takeIf { it.isNotEmpty() },
                    countryCode = sa.optString("countryCode").takeIf { it.isNotEmpty() },
                    postalCode = sa.optString("postalCode").takeIf { it.isNotEmpty() }
                )
            } else null

            VioGooglePayResult(token, email, shippingContact)
        } catch (e: Exception) {
            VioLogger.error("Error parsing Google Pay result: ${e.message}", TAG)
            null
        }
    }

    /**
     * Extracts shipping information from Google Pay PaymentData.
     */
    fun extractShippingContact(data: Intent?): VioShippingContact? {
        val paymentData = PaymentData.getFromIntent(data ?: return null) ?: return null
        val paymentInformation = paymentData.toJson()
        
        return try {
            val root = JSONObject(paymentInformation)
            
            if (root.has("shippingAddress")) {
                val sa = root.getJSONObject("shippingAddress")
                VioShippingContact(
                    name = sa.optString("name").takeIf { it.isNotEmpty() },
                    phone = sa.optString("phoneNumber").takeIf { it.isNotEmpty() },
                    address1 = sa.optString("address1").takeIf { it.isNotEmpty() },
                    address2 = sa.optString("address2").takeIf { it.isNotEmpty() },
                    city = sa.optString("locality").takeIf { it.isNotEmpty() },
                    administrativeArea = sa.optString("administrativeArea").takeIf { it.isNotEmpty() },
                    countryCode = sa.optString("countryCode").takeIf { it.isNotEmpty() },
                    postalCode = sa.optString("postalCode").takeIf { it.isNotEmpty() }
                )
            } else {
                null
            }
        } catch (e: Exception) {
            VioLogger.error("Error extracting shipping contact: ${e.message}", TAG)
            null
        }
    }

    /**
     * Extrae información de dirección del resultado de Google Pay.
     * @deprecated Use [extractShippingContact] instead for specific shipping info.
     */
    fun extractAddressFromPaymentData(data: Intent?): Map<String, Any?>? {
        val paymentData = PaymentData.getFromIntent(data ?: return null) ?: return null
        val paymentInformation = paymentData.toJson()
        
        return try {
            val root = JSONObject(paymentInformation)
            val result = mutableMapOf<String, Any?>()
            
            // Shipping Address
            if (root.has("shippingAddress")) {
                val sa = root.getJSONObject("shippingAddress")
                result["shippingAddress"] = mapOf(
                    "name" to sa.optString("name"),
                    "address1" to sa.optString("address1"),
                    "address2" to sa.optString("address2"),
                    "city" to sa.optString("locality"),
                    "administrativeArea" to sa.optString("administrativeArea"),
                    "countryCode" to sa.optString("countryCode"),
                    "postalCode" to sa.optString("postalCode"),
                    "phone" to sa.optString("phoneNumber")
                )
            }
            
            // Billing Address (comes inside paymentMethodData.info.billingAddress)
            val pmd = root.getJSONObject("paymentMethodData")
            if (pmd.has("info")) {
                val info = pmd.getJSONObject("info")
                if (info.has("billingAddress")) {
                    val ba = info.getJSONObject("billingAddress")
                    result["billingAddress"] = mapOf(
                        "name" to ba.optString("name"),
                        "address1" to ba.optString("address1"),
                        "address2" to ba.optString("address2"),
                        "city" to ba.optString("locality"),
                        "administrativeArea" to ba.optString("administrativeArea"),
                        "countryCode" to ba.optString("countryCode"),
                        "postalCode" to ba.optString("postalCode"),
                        "phone" to ba.optString("phoneNumber")
                    )
                }
            }
            
            val email = root.optString("email")
            if (email.isNotEmpty()) result["email"] = email
            
            result
        } catch (e: Exception) {
            VioLogger.error("Error extracting address: ${e.message}", TAG)
            null
        }
    }

    /**
     * Parses the token string from Google Pay/Stripe and extracts the actual token ID.
     * Google Pay with Stripe returns a JSON string, which sometimes can be nested.
     * Extraction logic:
     * 1. If it's a simple string like "tok_...", return it.
     * 2. If it's a JSON object, look for "id" at the root.
     * 3. Look for "card.token" as a JSON string and parse it recursively.
     */
    private fun extractTokenId(token: String): String {
        if (!token.trim().startsWith("{")) return token

        return try {
            val json = JSONObject(token)
            
            // Case 1: {"id": "tok_..."}
            if (json.has("id")) {
                return json.getString("id")
            }

            // Case 2: {"type":"card","card":{"token":"{\n  \"id\": \"tok_...\"}"}}
            if (json.has("card")) {
                val card = json.getJSONObject("card")
                if (card.has("token")) {
                    val nestedToken = card.getString("token")
                    return extractTokenId(nestedToken)
                }
            }

            // Fallback: return as-is if no known structure found
            token
        } catch (e: Exception) {
            token
        }
    }
}
