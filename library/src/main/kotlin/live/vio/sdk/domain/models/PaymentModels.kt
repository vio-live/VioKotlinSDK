package live.vio.sdk.domain.models

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize

// Payment methods list (migrated to match Swift)
data class GetAvailablePaymentMethodsDto(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("config") val config: List<PaymentConfigFieldDto>? = null,
)

data class PaymentConfigFieldDto(
    @JsonProperty("type") val type: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("value")
    @JsonSerialize(using = PaymentConfigValue.Serializer::class)
    @JsonDeserialize(using = PaymentConfigValue.Deserializer::class)
    val value: PaymentConfigValue?,
)

sealed class PaymentConfigValue {
    data class StringValue(val value: String) : PaymentConfigValue()
    data class NumberValue(val value: Double) : PaymentConfigValue()
    data class BooleanValue(val value: Boolean) : PaymentConfigValue()
    data object NullValue : PaymentConfigValue()

    internal class Deserializer : JsonDeserializer<PaymentConfigValue?>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PaymentConfigValue? {
            return when (p.currentToken()) {
                JsonToken.VALUE_NULL -> NullValue
                JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE -> BooleanValue(p.booleanValue)
                JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> NumberValue(p.doubleValue)
                JsonToken.VALUE_STRING -> StringValue(p.valueAsString)
                else -> {
                    ctxt.reportInputMismatch<PaymentConfigValue>(PaymentConfigValue::class.java, "Unsupported value type in PaymentConfigValue")
                    null
                }
            }
        }
    }

    internal class Serializer : JsonSerializer<PaymentConfigValue?>() {
        override fun serialize(value: PaymentConfigValue?, gen: JsonGenerator, serializers: SerializerProvider) {
            when (value) {
                null -> gen.writeNull()
                is StringValue -> gen.writeString(value.value)
                is NumberValue -> gen.writeNumber(value.value)
                is BooleanValue -> gen.writeBoolean(value.value)
                is NullValue -> gen.writeNull()
            }
        }
    }
}

data class PaymentIntentStripeDto(
    @JsonProperty("client_secret") val clientSecret: String = "",
    @JsonProperty("customer") val customer: String = "",
    @JsonProperty("publishable_key") val publishableKey: String = "",
    @JsonProperty("ephemeral_key") val ephemeralKey: String? = null,
    /** Stripe Connect (ADR-0022): the seller's connected account. Null otherwise. */
    @JsonProperty("stripe_account") val stripeAccount: String? = null,
)

data class InitPaymentStripeDto(
    @JsonProperty("checkout_url") val checkoutUrl: String = "",
    @JsonProperty("order_id") val orderId: Int = 0,
)

data class InitPaymentVippsDto(
    @JsonProperty("payment_url") val paymentUrl: String = "",
)

data class InitPaymentKlarnaDto(
    @JsonProperty("order_id") val orderId: String = "",
    @JsonProperty("status") val status: String = "",
    @JsonProperty("checkout_url") val checkoutUrl: String? = null,
    @JsonProperty("locale") val locale: String = "",
    @JsonProperty("html_snippet") val htmlSnippet: String = "",
)

data class KlarnaNativeAssetUrlsDto(
    @JsonProperty("descriptive") val descriptive: String? = null,
    @JsonProperty("standard") val standard: String? = null,
)

data class KlarnaNativePaymentMethodCategoryDto(
    @JsonProperty("identifier") val identifier: String = "",
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("asset_urls") val assetUrls: KlarnaNativeAssetUrlsDto? = null,
)

data class KlarnaNativeOrderLineDto(
    @JsonProperty("type") val type: String = "",
    @JsonProperty("name") val name: String = "",
    @JsonProperty("quantity") val quantity: Int = 0,
    @JsonProperty("unit_price") val unitPrice: Int = 0,
    @JsonProperty("total_amount") val totalAmount: Int = 0,
    @JsonProperty("tax_rate") val taxRate: Int = 0,
    @JsonProperty("tax_amount") val taxAmount: Int = 0,
)

data class KlarnaNativeOrderDto(
    @JsonProperty("order_id") val orderId: String = "",
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("locale") val locale: String? = null,
    @JsonProperty("html_snippet") val htmlSnippet: String? = null,
    @JsonProperty("purchase_country") val purchaseCountry: String = "",
    @JsonProperty("purchase_currency") val purchaseCurrency: String = "",
    @JsonProperty("order_amount") val orderAmount: Int = 0,
    @JsonProperty("order_tax_amount") val orderTaxAmount: Int? = null,
    @JsonProperty("payment_method_categories") val paymentMethodCategories: List<KlarnaNativePaymentMethodCategoryDto>? = null,
    @JsonProperty("order_lines") val orderLines: List<KlarnaNativeOrderLineDto>? = null,
)

data class InitPaymentKlarnaNativeDto(
    @JsonProperty("client_token") val clientToken: String = "",
    @JsonProperty("session_id") val sessionId: String = "",
    @JsonProperty("purchase_country") val purchaseCountry: String = "",
    @JsonProperty("purchase_currency") val purchaseCurrency: String = "",
    @JsonProperty("cart_id") val cartId: String = "",
    @JsonProperty("checkout_id") val checkoutId: String = "",
    @JsonProperty("payment_method_categories") val paymentMethodCategories: List<KlarnaNativePaymentMethodCategoryDto>? = null,
)

data class ConfirmPaymentKlarnaNativeDto(
    @JsonProperty("order_id") val orderId: String = "",
    @JsonProperty("checkout_id") val checkoutId: String = "",
    @JsonProperty("fraud_status") val fraudStatus: String? = null,
    @JsonProperty("order") val order: KlarnaNativeOrderDto? = null,
)

data class KlarnaNativeAddressInputDto(
    @JsonProperty("given_name") var givenName: String? = null,
    @JsonProperty("family_name") var familyName: String? = null,
    @JsonProperty("email") var email: String? = null,
    @JsonProperty("phone") var phone: String? = null,
    @JsonProperty("street_address") var streetAddress: String? = null,
    @JsonProperty("street_address2") var streetAddress2: String? = null,
    @JsonProperty("city") var city: String? = null,
    @JsonProperty("region") var region: String? = null,
    @JsonProperty("postal_code") var postalCode: String? = null,
    @JsonProperty("country") var country: String? = null,
)

data class KlarnaNativeCustomerInputDto(
    @JsonProperty("email") var email: String? = null,
    @JsonProperty("phone") var phone: String? = null,
    @JsonProperty("dob") var dob: String? = null,
    @JsonProperty("type") var type: String? = null,
    @JsonProperty("organization_registration_id") var organizationRegistrationId: String? = null,
)

data class KlarnaNativeInitInputDto(
    @JsonProperty("country_code") var countryCode: String? = null,
    @JsonProperty("currency") var currency: String? = null,
    @JsonProperty("locale") var locale: String? = null,
    @JsonProperty("return_url") var returnUrl: String? = null,
    @JsonProperty("intent") var intent: String? = null,
    @JsonProperty("auto_capture") var autoCapture: Boolean? = null,
    @JsonProperty("customer") var customer: KlarnaNativeCustomerInputDto? = null,
    @JsonProperty("billing_address") var billingAddress: KlarnaNativeAddressInputDto? = null,
    @JsonProperty("shipping_address") var shippingAddress: KlarnaNativeAddressInputDto? = null,
)

data class KlarnaNativeConfirmInputDto(
    @JsonProperty("authorization_token") val authorizationToken: String,
    @JsonProperty("auto_capture") var autoCapture: Boolean? = null,
    @JsonProperty("customer") var customer: KlarnaNativeCustomerInputDto? = null,
    @JsonProperty("billing_address") var billingAddress: KlarnaNativeAddressInputDto? = null,
    @JsonProperty("shipping_address") var shippingAddress: KlarnaNativeAddressInputDto? = null,
)

data class InitGooglePayDto(
    @JsonProperty("gateway") val gateway: String = "",
    @JsonProperty("gateway_merchant_id") val gatewayMerchantId: String = "",
    /** Stripe Connect (ADR-0022): the seller's connected account. Null otherwise. */
    @JsonProperty("stripe_account") val stripeAccount: String? = null,
)

data class ConfirmGooglePayDto(
    @JsonProperty("order_id") val orderId: String = "",
    @JsonProperty("status") val status: String = "",
)

data class VioShippingContact(
    val name: String?,
    val phone: String?,
    val address1: String?,
    val address2: String?,
    val city: String?,
    val administrativeArea: String?,
    val countryCode: String?,
    val postalCode: String?
)

data class ShippingAddressInputDto(
    @JsonProperty("first_name") val firstName: String? = null,
    @JsonProperty("last_name") val lastName: String? = null,
    @JsonProperty("zip") val zip: String? = null,
    @JsonProperty("city") val city: String? = null,
    @JsonProperty("phone_code") val phoneCode: String? = null,
    @JsonProperty("phone") val phone: String? = null,
    @JsonProperty("company") val company: String? = null,
    @JsonProperty("address1") val address1: String? = null,
    @JsonProperty("address2") val address2: String? = null,
    @JsonProperty("province") val province: String? = null,
    @JsonProperty("province_code") val provinceCode: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("country_code") val countryCode: String? = null,
)
