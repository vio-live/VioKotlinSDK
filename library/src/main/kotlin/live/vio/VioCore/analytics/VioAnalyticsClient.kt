package live.vio.VioCore.analytics

import live.vio.VioCore.utils.VioContextManager
import live.vio.VioCore.utils.VioLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Vio Analytics — contract-v1 transport for Android / Android TV (F5).
 *
 * Sends events to the Vio collector (`vio-live/vio-analytics`,
 * `POST <eventsBase>/v1/events`), which owns raw storage (ClickHouse) and any
 * vendor fan-out server-side. THE SDK NEVER TALKS TO VENDORS — the legacy
 * Mixpanel path in [AnalyticsManager] stays only for hosts that still use it.
 *
 * Wire contract: `vio-analytics/docs/EVENTS_CONTRACT.md` (snake_case JSON,
 * additive-only v1). Mechanics per the contract's platform table:
 *  - `anon_id` persists in SharedPreferences (`vio.anon.v1`)
 *  - rolling 30-min session (`vio.session.v1`), `session_start` on rotation
 *  - batch flush: 20 events / 5 s; offline queue persisted to filesDir,
 *    capped at 500 drop-oldest; `event_id` stable so retries dedupe
 *  - clients never name their tenant — the api key does.
 *
 * Mirrors the Swift `VioAnalyticsClient` — keep both in sync.
 */
object VioAnalyticsClient {

    private const val COMPONENT = "VioAnalyticsClient"
    private const val SESSION_TTL_MS = 30L * 60 * 1000
    private const val FLUSH_INTERVAL_MS = 5_000L
    private const val FLUSH_AT_COUNT = 20
    private const val MAX_QUEUE = 500
    private const val MAX_BATCH = 500
    private val RETRY_BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000)
    private const val PREFS = "vio.analytics"
    private const val ANON_KEY = "vio.anon.v1"
    private const val SESSION_KEY = "vio.session.v1"
    private const val QUEUE_FILE = "vio-analytics-queue.json"
    const val SDK_VERSION = "1.0.0" // keep in sync with release tags

    // ── Wire format ─────────────────────────────────────────────────────────

    @Serializable
    data class Context(
        @SerialName("campaign_id") val campaignId: Int? = null,
        @SerialName("broadcast_id") val broadcastId: String? = null,
        @SerialName("campaign_component_id") val campaignComponentId: Int? = null,
        @SerialName("app_placement_id") val appPlacementId: Int? = null,
        @SerialName("location_id") val locationId: String? = null,
        @SerialName("component_template_id") val componentTemplateId: String? = null,
        @SerialName("sponsor_id") val sponsorId: Int? = null,
        @SerialName("activation_id") val activationId: Int? = null,
        @SerialName("tv_session_id") val tvSessionId: Int? = null,
        @SerialName("content_url") val contentUrl: String? = null,
        val variant: String? = null,
    )

    @Serializable
    data class Item(
        @SerialName("product_id") val productId: String,
        val name: String? = null,
        val brand: String? = null,
        @SerialName("variant_id") val variantId: String? = null,
        val price: Double? = null,
        val quantity: Int? = null,
    )

    @Serializable
    data class Commerce(
        val items: List<Item>? = null,
        val value: Double? = null,
        val currency: String? = null,
        @SerialName("order_id") val orderId: String? = null,
        @SerialName("payment_method") val paymentMethod: String? = null,
    )

    @Serializable
    data class WireEvent(
        @SerialName("event_id") val eventId: String,
        val name: String,
        val ts: String,
        val surface: String,
        @SerialName("sdk_version") val sdkVersion: String,
        @SerialName("session_id") val sessionId: String,
        @SerialName("anon_id") val anonId: String,
        @SerialName("external_user_id") val externalUserId: String? = null,
        val context: Context? = null,
        val commerce: Commerce? = null,
        val props: JsonObject? = null,
    )

    @Serializable
    private data class Envelope(
        val apiKey: String,
        @SerialName("sent_at") val sentAt: String,
        val events: List<WireEvent>,
    )

    @Serializable
    private data class StoredSession(val id: String, val ts: Long, val startedAt: Long)

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    // ── State ───────────────────────────────────────────────────────────────

    @Volatile private var enabled = false
    @Volatile private var eventsBase = ""
    @Volatile private var apiKeyProvider: () -> String? = { null }
    @Volatile private var externalUserId: String? = null

    private val lock = Any()
    private val queue = ArrayDeque<WireEvent>()
    private var retryAttempt = 0
    private var retryNotBefore = 0L
    private var flushJob: Job? = null

    private var sessionId: String? = null
    private var sessionStartedAt = 0L
    /** In-memory fallbacks when no Context is attached (unit tests). */
    private var memoryAnonId: String? = null
    private var memorySession: StoredSession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seams. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }
    internal var sender: (suspend (List<WireEvent>, String, String) -> Boolean)? = null

    // ── Public API ──────────────────────────────────────────────────────────

    /** Activate the collector transport. Called from [AnalyticsManager.configure]. */
    fun start(eventsBase: String, apiKeyProvider: () -> String?) {
        this.eventsBase = eventsBase.trimEnd('/')
        this.apiKeyProvider = apiKeyProvider
        this.enabled = true
        loadPersistedQueue()
        ensureSession()

        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
        VioLogger.debug("Vio collector transport started ($eventsBase)", COMPONENT)
    }

    fun stop() {
        enabled = false
        flushJob?.cancel()
        flushJob = null
    }

    /** Partner's opaque user id. NEVER auto-derived from emails/PII. */
    fun identify(externalUserId: String?) {
        this.externalUserId = externalUserId
    }

    /** Current rolling session id — [AnalyticsManager] scopes its
     *  once-per-session impression guard to this. */
    val currentSessionId: String
        get() {
            ensureSession()
            return sessionId ?: ""
        }

    fun track(
        name: String,
        context: Context? = null,
        commerce: Commerce? = null,
        props: JsonObject? = null,
    ) {
        if (!enabled) return
        ensureSession()
        val session = sessionId ?: return

        val event = WireEvent(
            eventId = UUID.randomUUID().toString(),
            name = name,
            ts = Instant.ofEpochMilli(nowProvider()).toString(),
            surface = surface,
            sdkVersion = SDK_VERSION,
            sessionId = session,
            anonId = anonId(),
            externalUserId = externalUserId,
            context = context,
            commerce = commerce,
            props = props,
        )
        val shouldFlush: Boolean
        synchronized(lock) {
            queue.addLast(event)
            while (queue.size > MAX_QUEUE) queue.removeFirst() // drop oldest
            shouldFlush = queue.size >= FLUSH_AT_COUNT
        }
        if (shouldFlush) scope.launch { flush() }
    }

    /** Host apps may call this from onStop/onPause to push pending events. */
    fun onAppBackground() {
        scope.launch {
            flush()
            persistQueue()
        }
    }

    suspend fun flush() {
        if (!enabled) return
        if (nowProvider() < retryNotBefore) return
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: return // not initialized — hold

        val batch: List<WireEvent> = synchronized(lock) {
            if (queue.isEmpty()) return
            queue.take(MAX_BATCH)
        }

        val ok = sender?.invoke(batch, eventsBase, apiKey) ?: send(batch, apiKey)

        if (ok) {
            synchronized(lock) { repeat(minOf(batch.size, queue.size)) { queue.removeFirst() } }
            retryAttempt = 0
        } else {
            // Same event_ids stay queued — the collector dedupes retries.
            val backoff = RETRY_BACKOFF_MS[minOf(retryAttempt, RETRY_BACKOFF_MS.size - 1)]
            retryAttempt++
            retryNotBefore = nowProvider() + backoff
            persistQueue()
        }
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private suspend fun send(batch: List<WireEvent>, apiKey: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL("$eventsBase/v1/events").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Api-Key", apiKey)
                val body = json.encodeToString(
                    Envelope(
                        apiKey = apiKey,
                        sentAt = Instant.ofEpochMilli(nowProvider()).toString(),
                        events = batch,
                    ),
                )
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                connection.disconnect()
                when (code) {
                    202 -> true
                    // Config problem, not transient — drop rather than loop forever.
                    400, 401 -> {
                        VioLogger.warning("Collector rejected batch (HTTP $code) — dropped", COMPONENT)
                        true
                    }
                    else -> false
                }
            } catch (t: Throwable) {
                VioLogger.debug("Collector flush failed: ${t.message}", COMPONENT)
                false
            }
        }

    // ── Identity & session ──────────────────────────────────────────────────

    private fun prefs() =
        appContext()?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun appContext(): android.content.Context? =
        if (VioContextManager.isInitialized) runCatching { VioContextManager.context }.getOrNull() else null

    internal fun anonId(): String {
        prefs()?.getString(ANON_KEY, null)?.let { return it }
        memoryAnonId?.let { return it }
        val fresh = "a-${UUID.randomUUID()}"
        prefs()?.edit()?.putString(ANON_KEY, fresh)?.apply() ?: run { memoryAnonId = fresh }
        return prefs()?.getString(ANON_KEY, null) ?: fresh
    }

    private fun loadStoredSession(): StoredSession? {
        val raw = prefs()?.getString(SESSION_KEY, null)
            ?: return memorySession
        return runCatching { json.decodeFromString<StoredSession>(raw) }.getOrNull()
    }

    private fun storeSession(stored: StoredSession) {
        memorySession = stored
        prefs()?.edit()?.putString(SESSION_KEY, json.encodeToString(stored))?.apply()
    }

    private fun ensureSession() {
        val now = nowProvider()
        if (sessionId == null) {
            loadStoredSession()?.let { stored ->
                if (now - stored.ts < SESSION_TTL_MS) {
                    sessionId = stored.id
                    sessionStartedAt = stored.startedAt
                }
            }
        } else {
            loadStoredSession()?.let { stored ->
                if (now - stored.ts >= SESSION_TTL_MS) sessionId = null // idle — rotate
            }
        }

        if (sessionId == null) {
            val fresh = "s-${UUID.randomUUID()}"
            sessionId = fresh
            sessionStartedAt = now
            storeSession(StoredSession(fresh, now, now))
            track(name = "session_start")
        } else {
            storeSession(StoredSession(sessionId!!, now, sessionStartedAt))
        }
    }

    // ── Offline persistence ─────────────────────────────────────────────────

    private fun queueFile(): File? = appContext()?.filesDir?.let { File(it, QUEUE_FILE) }

    private fun persistQueue() {
        val file = queueFile() ?: return
        val snapshot = synchronized(lock) { queue.toList() }
        runCatching {
            if (snapshot.isEmpty()) file.delete()
            else file.writeText(json.encodeToString(snapshot))
        }
    }

    private fun loadPersistedQueue() {
        val file = queueFile() ?: return
        if (!file.exists()) return
        runCatching {
            val restored = json.decodeFromString<List<WireEvent>>(file.readText())
            synchronized(lock) {
                restored.takeLast(MAX_QUEUE).asReversed().forEach { queue.addFirst(it) }
                while (queue.size > MAX_QUEUE) queue.removeLast()
            }
        }
        file.delete()
    }

    // ── Platform ────────────────────────────────────────────────────────────

    internal val surface: String
        get() {
            val context = appContext() ?: return "android"
            val isTv = runCatching {
                context.packageManager.hasSystemFeature("android.software.leanback")
            }.getOrDefault(false)
            return if (isTv) "androidtv" else "android"
        }

    /** Collector base per environment (override via AnalyticsConfiguration.eventsBase). */
    fun defaultEventsBase(isProduction: Boolean): String =
        if (isProduction) "https://events.vio.live" else "https://events-dev.vio.live"

    /** Test hook — full reset. */
    internal fun resetForTesting() {
        stop()
        synchronized(lock) { queue.clear() }
        sessionId = null
        sessionStartedAt = 0
        memoryAnonId = null
        memorySession = null
        externalUserId = null
        retryAttempt = 0
        retryNotBefore = 0
        nowProvider = { System.currentTimeMillis() }
        sender = null
    }
}
