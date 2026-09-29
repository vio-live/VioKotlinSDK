package live.vio.sdk.core.graphql.operations

object PaymentGraphQL {
    val GET_AVAILABLE_METHODS_PAYMENT_QUERY = """
    query GetAvailablePaymentMethods {
      Payment {
        GetAvailablePaymentMethods {
          name
          config {
            name
            type
            value
          }
        }
      }
    }
    """.trimIndent()
    val STRIPE_INTENT_PAYMENT_MUTATION = """
    mutation CreatePaymentIntentStripe(${'$'}checkoutId: String!, ${'$'}returnEphemeralKey: Boolean) {
      Payment {
        CreatePaymentIntentStripe(
          checkout_id: ${'$'}checkoutId
          return_ephemeral_key: ${'$'}returnEphemeralKey
        ) {
          client_secret
          customer
          publishable_key
          stripe_account
          ephemeral_key
        }
      }
    }
    """.trimIndent()
    val STRIPE_PLATFORM_BUILDER_PAYMENT_MUTATION = """
    mutation CreatePaymentStripe(
      ${'$'}checkoutId: String!
      ${'$'}successUrl: String!
      ${'$'}paymentMethod: String!
      ${'$'}email: String!
    ) {
      Payment {
        CreatePaymentStripe(
          checkout_id: ${'$'}checkoutId
          success_url: ${'$'}successUrl
          payment_method: ${'$'}paymentMethod
          email: ${'$'}email
        ) {
          checkout_url
          order_id
        }
      }
    }
    """.trimIndent()
    val KLARNA_PLATFORM_BUILDER_PAYMENT_MUTATION = """
    mutation Payment(${'$'}checkoutId: String!, ${'$'}countryCode: String!, ${'$'}href: String!, ${'$'}email: String!) {
      Payment {
        CreatePaymentKlarna(
          checkout_id: ${'$'}checkoutId
          country_code: ${'$'}countryCode
          href: ${'$'}href
          email: ${'$'}email
        ) {
          order_id
          status
          locale
          html_snippet
        }
      }
    }
    """.trimIndent()
    val KLARNA_NATIVE_INIT_PAYMENT_MUTATION = """
    mutation CreatePaymentKlarnaNative(
      ${'$'}shippingAddress: KlarnaNativeAddressInput
      ${'$'}checkoutId: String!
      ${'$'}countryCode: String
      ${'$'}currency: String
      ${'$'}locale: String
      ${'$'}returnUrl: String
      ${'$'}intent: String
      ${'$'}autoCapture: Boolean
      ${'$'}customer: KlarnaNativeCustomerInput
      ${'$'}billingAddress: KlarnaNativeAddressInput
    ) {
      Payment {
        CreatePaymentKlarnaNative(
          shipping_address: ${'$'}shippingAddress
          checkout_id: ${'$'}checkoutId
          country_code: ${'$'}countryCode
          currency: ${'$'}currency
          locale: ${'$'}locale
          return_url: ${'$'}returnUrl
          intent: ${'$'}intent
          auto_capture: ${'$'}autoCapture
          customer: ${'$'}customer
          billing_address: ${'$'}billingAddress
        ) {
          cart_id
          checkout_id
          client_token
          purchase_country
          purchase_currency
          session_id
          payment_method_categories {
            identifier
            name
            asset_urls {
              descriptive
              standard
            }
          }
        }
      }
    }
    """.trimIndent()
    val KLARNA_NATIVE_CONFIRM_PAYMENT_MUTATION = """
    mutation ConfirmPaymentKlarnaNative(
      ${'$'}checkoutId: String!
      ${'$'}authorizationToken: String!
      ${'$'}autoCapture: Boolean
      ${'$'}billingAddress: KlarnaNativeAddressInput
      ${'$'}shippingAddress: KlarnaNativeAddressInput
      ${'$'}customer: KlarnaNativeCustomerInput
    ) {
      Payment {
        ConfirmPaymentKlarnaNative(
          checkout_id: ${'$'}checkoutId
          authorization_token: ${'$'}authorizationToken
          auto_capture: ${'$'}autoCapture
          billing_address: ${'$'}billingAddress
          shipping_address: ${'$'}shippingAddress
          customer: ${'$'}customer
        ) {
          order_id
          checkout_id
          fraud_status
          order {
            order_id
            status
            purchase_country
            purchase_currency
            locale
            billing_address {
              given_name
              family_name
              email
              street_address
              postal_code
              city
              country
            }
            shipping_address {
              given_name
              family_name
              email
              street_address
              postal_code
              city
              country
            }
            order_amount
            order_tax_amount
            total_line_items_price
            order_lines {
              type
              name
              quantity
              unit_price
              tax_rate
              total_amount
              total_discount_amount
              total_tax_amount
              merchant_data
            }
            merchant_urls {
              terms
              checkout
              confirmation
              push
            }
            html_snippet
            started_at
            last_modified_at
            options {
              allow_separate_shipping_address
              date_of_birth_mandatory
              require_validate_callback_success
              phone_mandatory
              auto_capture
            }
            shipping_options {
              id
              name
              price
              tax_amount
              tax_rate
              preselected
            }
            merchant_data
            selected_shipping_option {
              id
              name
              price
              tax_amount
              tax_rate
              preselected
            }
          }
        }
      }
    }
    """.trimIndent()
    val KLARNA_NATIVE_ORDER_QUERY = """
    query GetKlarnaOrderNative(${'$'}orderId: String!, ${'$'}userId: String) {
      Payment {
        GetKlarnaOrderNative(order_id: ${'$'}orderId, user_id: ${'$'}userId) {
          order_id
          status
          locale
          html_snippet
          purchase_country
          purchase_currency
          order_amount
          order_tax_amount
          payment_method_categories {
            identifier
            name
          }
          order_lines {
            type
            name
            quantity
            unit_price
            total_amount
            tax_rate
            tax_amount
          }
        }
      }
    }
    """.trimIndent()
    val VIPPS_PAYMENT = """
    mutation CreatePaymentVipps(${'$'}checkoutId: String!, ${'$'}email: String!, ${'$'}returnUrl: String!) {
      Payment {
        CreatePaymentVipps(checkout_id: ${'$'}checkoutId, email: ${'$'}email, return_url: ${'$'}returnUrl) {
          payment_url
        }
      }
    }
    """.trimIndent()

    val GOOGLE_PAY_INIT_MUTATION = """
    mutation CreatePaymentGooglePay(${'$'}checkoutId: String!) {
      Payment {
        CreatePaymentGooglePay(checkout_id: ${'$'}checkoutId) {
          gateway
          gateway_merchant_id
        }
      }
    }
    """.trimIndent()

    val GOOGLE_PAY_CONFIRM_MUTATION = """
    mutation ConfirmPaymentGooglePay(${'$'}checkoutId: String!, ${'$'}googlePayToken: String!, ${'$'}email: String, ${'$'}shippingAddress: GooglePayAddressInput) {
      Payment {
        ConfirmPaymentGooglePay(
          checkout_id: ${'$'}checkoutId, 
          google_pay_token: ${'$'}googlePayToken,
          email: ${'$'}email,
          shipping_address: ${'$'}shippingAddress
        ) {
          order_id
          status
        }
      }
    }
    """.trimIndent()
}
