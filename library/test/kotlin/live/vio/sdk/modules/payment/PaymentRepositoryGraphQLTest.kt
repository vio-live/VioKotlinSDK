package live.vio.sdk.modules.payment

import kotlinx.coroutines.runBlocking
import live.vio.sdk.core.graphql.GraphQLHttpClient
import live.vio.sdk.core.graphql.GraphQLHttpResponse
import live.vio.sdk.domain.models.ShippingAddressInputDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class PaymentRepositoryGraphQLTest {

    private val mockClient: GraphQLHttpClient = mockk()
    private val repository = PaymentRepositoryGraphQL(mockClient)

    @Test
    fun `googlePayInit calls correct mutation and parses response`() = runBlocking {
        val checkoutId = "test-checkout-id"
        val expectedData = mapOf(
            "Payment" to mapOf(
                "CreatePaymentGooglePay" to mapOf(
                    "gateway" to "test-gateway",
                    "gateway_merchant_id" to "test-merchant-id"
                )
            )
        )
        val response = GraphQLHttpResponse(data = expectedData, errors = null, status = 200)

        coEvery { mockClient.runMutationSafe(any(), any()) } returns response

        val result = repository.googlePayInit(checkoutId)

        assertNotNull(result)
        assertEquals("test-gateway", result.gateway)
        assertEquals("test-merchant-id", result.gatewayMerchantId)

        coVerify { 
            mockClient.runMutationSafe(
                match { it.contains("mutation CreatePaymentGooglePay") && it.contains("CreatePaymentGooglePay(checkout_id: ${'$'}checkoutId)") },
                match { it["checkoutId"] == checkoutId }
            ) 
        }
    }

    @Test
    fun `googlePayConfirm calls correct mutation and parses response`() = runBlocking {
        val checkoutId = "test-checkout-id"
        val token = "test-token"
        val email = "test@example.com"
        val shippingAddress = ShippingAddressInputDto(
            firstName = "John",
            lastName = "Doe",
            zip = "12345",
            city = "Oslo",
            countryCode = "NO",
            phone = "12345678"
        )

        val expectedData = mapOf(
            "Payment" to mapOf(
                "ConfirmPaymentGooglePay" to mapOf(
                    "status" to "SUCCESS",
                    "order_id" to "ORDER-123"
                )
            )
        )
        val response = GraphQLHttpResponse(data = expectedData, errors = null, status = 200)

        coEvery { mockClient.runMutationSafe(any(), any()) } returns response

        val result = repository.googlePayConfirm(checkoutId, token, email, shippingAddress)

        assertNotNull(result)
        assertEquals("SUCCESS", result.status)
        assertEquals("ORDER-123", result.orderId)

        coVerify { 
            mockClient.runMutationSafe(
                match { 
                    it.contains("mutation ConfirmPaymentGooglePay") && 
                    it.contains("ConfirmPaymentGooglePay(") &&
                    it.contains("checkout_id: ${'$'}checkoutId") &&
                    it.contains("google_pay_token: ${'$'}googlePayToken") &&
                    it.contains("email: ${'$'}email") &&
                    it.contains("shipping_address: ${'$'}shippingAddress")
                },
                match { 
                    it["checkoutId"] == checkoutId &&
                    it["googlePayToken"] == token &&
                    it["email"] == email &&
                    it["shippingAddress"] != null
                }
            ) 
        }
    }

    @Test
    fun `stripe intent carries the Connect account when there is one`() = runBlocking {
        val connect = mapOf(
            "Payment" to mapOf(
                "CreatePaymentIntentStripe" to mapOf(
                    "client_secret" to "pi_1_secret_x",
                    "customer" to "cus_1",
                    "publishable_key" to "pk_test_platform",
                    "stripe_account" to "acct_1Seller"
                )
            )
        )
        coEvery { mockClient.runMutationSafe(any(), any()) } returns
            GraphQLHttpResponse(data = connect, errors = null, status = 200)
        val result = repository.stripeIntent("checkout-1", null)
        assertEquals("acct_1Seller", result.stripeAccount)
        coVerify { mockClient.runMutationSafe(match { it.contains("stripe_account") }, any()) }
    }

    @Test
    fun `stripe intent without Connect has no account`() = runBlocking {
        val plain = mapOf(
            "Payment" to mapOf(
                "CreatePaymentIntentStripe" to mapOf(
                    "client_secret" to "pi_1_secret_x",
                    "customer" to "cus_1",
                    "publishable_key" to "pk_test_seller",
                    "stripe_account" to null
                )
            )
        )
        coEvery { mockClient.runMutationSafe(any(), any()) } returns
            GraphQLHttpResponse(data = plain, errors = null, status = 200)
        val result = repository.stripeIntent("checkout-1", null)
        assertEquals(null, result.stripeAccount)
        assertEquals("pk_test_seller", result.publishableKey)
    }

    @Test
    fun `googlePayInit carries the Connect account when there is one`() = runBlocking {
        val data = mapOf(
            "Payment" to mapOf(
                "CreatePaymentGooglePay" to mapOf(
                    "gateway" to "stripe",
                    "gateway_merchant_id" to "pk_test_platform",
                    "stripe_account" to "acct_1Seller"
                )
            )
        )
        coEvery { mockClient.runMutationSafe(any(), any()) } returns
            GraphQLHttpResponse(data = data, errors = null, status = 200)
        val result = repository.googlePayInit("checkout-1")
        assertEquals("acct_1Seller", result.stripeAccount)
    }
}
