package live.vio.VioCore.analytics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Contract-v1 transport — wire format, sessions, retry semantics. Runs on the
 * JVM without an Android Context (the client falls back to in-memory identity
 * storage, which is exactly what these tests need).
 */
class VioAnalyticsClientTest {

    private val sent = mutableListOf<List<VioAnalyticsClient.WireEvent>>()
    private var sendResult = true
    private var now = 1_700_000_000_000L

    @BeforeEach
    fun setUp() {
        VioAnalyticsClient.resetForTesting()
        VioAnalyticsClient.nowProvider = { now }
        VioAnalyticsClient.sender = { batch, _, _ ->
            sent += batch
            sendResult
        }
        VioAnalyticsClient.start("https://events.test") { "test-key" }
    }

    @AfterEach
    fun tearDown() {
        VioAnalyticsClient.resetForTesting()
    }

    @Test
    fun `wire format is snake_case contract v1`() {
        VioAnalyticsClient.track(
            name = "component_impression",
            context = VioAnalyticsClient.Context(
                campaignId = 44, campaignComponentId = 512, sponsorId = 9, variant = "top-b",
            ),
            commerce = VioAnalyticsClient.Commerce(
                items = listOf(VioAnalyticsClient.Item(productId = "408948", price = 300.0)),
                value = 300.0,
                currency = "NOK",
            ),
        )
        runBlocking { VioAnalyticsClient.flush() }

        val batch = sent.single()
        val event = batch.last()
        val json = Json.parseToJsonElement(Json.encodeToString(event)).jsonObject

        assertEquals("component_impression", json["name"]!!.jsonPrimitive.content)
        assertEquals("android", json["surface"]!!.jsonPrimitive.content)
        assertTrue(json["session_id"]!!.jsonPrimitive.content.startsWith("s-"))
        assertTrue(json["anon_id"]!!.jsonPrimitive.content.startsWith("a-"))
        val context = json["context"]!!.jsonObject
        assertEquals(512, context["campaign_component_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("top-b", context["variant"]!!.jsonPrimitive.content)
        val commerce = json["commerce"]!!.jsonObject
        assertEquals("NOK", commerce["currency"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session_start emitted once, session rotates after 30 min idle`() {
        VioAnalyticsClient.track(name = "view_item")
        VioAnalyticsClient.track(name = "view_item")
        runBlocking { VioAnalyticsClient.flush() }

        val names = sent.single().map { it.name }
        assertEquals(1, names.count { it == "session_start" })
        assertEquals(2, names.count { it == "view_item" })
        val firstSession = sent.single().last().sessionId

        now += 31 * 60 * 1000 // 31 min idle
        VioAnalyticsClient.track(name = "view_item")
        runBlocking { VioAnalyticsClient.flush() }

        val second = sent.last()
        assertTrue(second.map { it.name }.contains("session_start")) // re-emitted on rotation
        assertNotEquals(firstSession, second.last().sessionId)
        // anon id survives rotation (same device)
        assertEquals(sent.first().last().anonId, second.last().anonId)
    }

    @Test
    fun `failed flush keeps events with the same ids and backs off`() {
        sendResult = false
        VioAnalyticsClient.track(name = "view_item")
        runBlocking { VioAnalyticsClient.flush() } // fails — requeued, backoff armed
        assertEquals(1, sent.size)

        runBlocking { VioAnalyticsClient.flush() } // inside backoff — no call
        assertEquals(1, sent.size)

        now += 10_000 // past backoff
        sendResult = true
        runBlocking { VioAnalyticsClient.flush() }
        assertEquals(2, sent.size)
        assertEquals(sent[0].map { it.eventId }, sent[1].map { it.eventId }) // stable ids
    }
}
