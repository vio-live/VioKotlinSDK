package live.vio.VioCore.analytics

import live.vio.VioCore.configuration.AnalyticsConfiguration
import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.utils.VioLogger
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight analytics dispatcher used to mirror the Swift SDK behaviour. The
 * default implementation simply broadcasts events to registered [AnalyticsSink]s
 * and logs them through [VioLogger]. Host apps can register a sink to forward
 * the payload to Mixpanel, Firebase, etc.
 */
object AnalyticsManager {

    private const val COMPONENT = "AnalyticsManager"
    private const val SDK_VERSION = "1.0.0"
    private const val SDK_PLATFORM = "android"

    fun interface AnalyticsSink {
        fun onEvent(name: String, properties: Map<String, Any?>)
    }

    private val sinks = CopyOnWriteArrayList<AnalyticsSink>()
    private var configuration: AnalyticsConfiguration = AnalyticsConfiguration.default()
    private var sessionId: String = UUID.randomUUID().toString()
    private val trackedComponentViews = mutableSetOf<String>()
    private val componentViewCounts = mutableMapOf<String, Int>()
    private val impressionTimers = mutableMapOf<String, Long>()
    /** Once-per-(collector session, component) guard for the Vio pipeline. */
    private val collectorImpressionKeys = mutableSetOf<String>()

    /** Legacy componentId strings can be a numeric campaign_component id or a
     *  template slug — route each to the right contract dimension. */
    private fun contractContext(
        componentId: String?,
        componentType: String?,
        campaignId: Int?,
    ): VioAnalyticsClient.Context {
        val numeric = componentId?.toIntOrNull()
        return VioAnalyticsClient.Context(
            campaignId = campaignId,
            campaignComponentId = numeric,
            componentTemplateId = when {
                numeric == null && componentId != null -> componentId
                else -> componentType
            },
        )
    }
    private var distinctId: String? = null
    private var mixpanelClient: MixpanelClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun configure(config: AnalyticsConfiguration) {
        configuration = config

        // Vio collector transport (F5) — the primary pipeline, independent
        // of the legacy Mixpanel path. Env-aware endpoint, api-key auth.
        if (config.sendToVio) {
            val isProduction =
                VioConfiguration.shared.state.value.environment.name == "PRODUCTION"
            val base = config.eventsBase ?: VioAnalyticsClient.defaultEventsBase(isProduction)
            VioAnalyticsClient.start(base) {
                VioConfiguration.shared.state.value.apiKey.takeIf { it.isNotBlank() }
            }
        }
        sessionId = UUID.randomUUID().toString()
        trackedComponentViews.clear()
        componentViewCounts.clear()
        impressionTimers.clear()
        distinctId = null
        mixpanelClient = config
            .takeIf { it.enabled }
            ?.mixpanelToken
            ?.takeIf { it.isNotBlank() }
            ?.let { token -> MixpanelClient(token, config.apiHost) }
        VioLogger.debug("Analytics configured (enabled=${config.enabled})", COMPONENT)
    }

    fun identify(userId: String) {
        distinctId = userId
        // Explicit partner user id — forwarded to the Vio pipeline as-is.
        // (Auto-identify from checkout emails below does NOT reach the
        // collector — PII never leaves for Vio.)
        if (userId != lastAutoIdentifiedEmail) {
            VioAnalyticsClient.identify(userId)
        }
    }

    /** Marks emails auto-identified by trackCheckoutStarted so they are
     *  kept OUT of the Vio pipeline (vendor-only legacy behaviour). */
    private var lastAutoIdentifiedEmail: String? = null

    fun setUserProperties(properties: Map<String, Any?>) {
        val client = mixpanelClient ?: return
        val sanitized = sanitizeProperties(properties)
        if (sanitized.isEmpty()) return
        val id = currentDistinctId()
        scope.launch {
            client.setPeopleProperties(id, sanitized)
        }
    }

    fun addSink(sink: AnalyticsSink) {
        sinks += sink
    }

    fun removeSink(sink: AnalyticsSink) {
        sinks -= sink
    }

    fun trackComponentView(
        componentId: String,
        componentType: String,
        componentName: String? = null,
        campaignId: Int? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val vendorOn = configuration.enabled && configuration.trackComponentViews
        val vioOn = configuration.sendToVio && configuration.trackComponentViews
        if (!vendorOn && !vioOn) return

        val activeCampaign = campaignId ?: CampaignManager.shared.currentCampaign.value?.id
        if (vioOn) {
            // Contract rule: ONE component_impression per (session, component),
            // scoped to the collector's rolling session.
            val collectorKey = "${'$'}{VioAnalyticsClient.currentSessionId}|${'$'}componentId-${'$'}componentType"
            if (collectorImpressionKeys.add(collectorKey)) {
                VioAnalyticsClient.track(
                    name = "component_impression",
                    context = contractContext(componentId, componentType, activeCampaign),
                )
            }
        }
        if (!vendorOn) return
        val state = VioConfiguration.shared.state.value
        val activeCampaignId = campaignId ?: CampaignManager.shared.currentCampaign.value?.id
        val properties = mutableMapOf<String, Any?>(
            "component_id" to componentId,
            "component_type" to componentType,
        )
        properties.putIfNotNull("component_name", componentName)
        properties.putIfNotNull("campaign_id", activeCampaignId)
        if (state.apiKey.isNotBlank()) {
            properties["project_id"] = state.apiKey
            properties["project_api_key"] = state.apiKey
        }
        metadata?.forEach { (key, value) ->
            if (value != null) properties[key] = value
        }

        val viewKey = "$componentId-$componentType"
        val totalViewCount = (componentViewCounts[viewKey] ?: 0) + 1
        componentViewCounts[viewKey] = totalViewCount
        properties["total_view_count"] = totalViewCount
        properties["view_count"] = totalViewCount

        val isFirstView = trackedComponentViews.add(viewKey)
        if (isFirstView) {
            track(formatComponentEventName(componentType, "Viewed"), properties)
            track("Component Viewed", properties)
        }
        track("Component Impression Count", properties)

        if (configuration.trackImpressions) {
            impressionTimers[componentId] = System.currentTimeMillis()
        }
    }

    fun trackComponentClick(
        componentId: String,
        componentType: String,
        action: String,
        componentName: String? = null,
        campaignId: Int? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        val vendorOn = configuration.enabled && configuration.trackComponentClicks
        val vioOn = configuration.sendToVio && configuration.trackComponentClicks
        if (!vendorOn && !vioOn) return
        if (vioOn) {
            VioAnalyticsClient.track(
                name = "component_click",
                context = contractContext(
                    componentId, componentType,
                    campaignId ?: CampaignManager.shared.currentCampaign.value?.id,
                ),
                props = kotlinx.serialization.json.buildJsonObject {
                    put("action", kotlinx.serialization.json.JsonPrimitive(action))
                },
            )
        }
        if (!vendorOn) return
        val properties = mutableMapOf<String, Any?>(
            "component_id" to componentId,
            "component_type" to componentType,
            "action" to action,
        )
        properties.putIfNotNull("component_name", componentName)
        properties.putIfNotNull("campaign_id", campaignId)
        metadata?.forEach { (key, value) ->
            if (value != null) properties[key] = value
        }
        track(formatComponentEventName(componentType, "Clicked"), properties)
    }

    fun trackComponentImpression(
        componentId: String,
        componentType: String,
        durationSeconds: Double,
    ) {
        if (!configuration.enabled || !configuration.trackImpressions) return
        val props = mapOf(
            "component_id" to componentId,
            "component_type" to componentType,
            "duration_seconds" to durationSeconds,
        )
        track("Component Impression", props)
    }

    fun trackProductViewed(
        productId: String,
        productName: String,
        productPrice: Double? = null,
        productCurrency: String? = null,
        source: String? = null,
        componentId: String? = null,
        componentType: String? = null,
    ) {
        val vendorOn = configuration.enabled && configuration.trackProductEvents
        val vioOn = configuration.sendToVio && configuration.trackProductEvents
        if (!vendorOn && !vioOn) return
        if (vioOn) {
            VioAnalyticsClient.track(
                name = "view_item",
                context = contractContext(
                    componentId, componentType,
                    CampaignManager.shared.currentCampaign.value?.id,
                ),
                commerce = VioAnalyticsClient.Commerce(
                    items = listOf(
                        VioAnalyticsClient.Item(
                            productId = productId, name = productName, price = productPrice,
                        ),
                    ),
                    value = productPrice,
                    currency = productCurrency,
                ),
            )
        }
        if (!vendorOn) return
        val props = mutableMapOf<String, Any?>(
            "product_id" to productId,
            "product_name" to productName,
        )
        props.putIfNotNull("product_price", productPrice)
        props.putIfNotNull("product_currency", productCurrency)
        props.putIfNotNull("source", source)
        props.putIfNotNull("component_id", componentId)
        props.putIfNotNull("component_type", componentType)
        track("Product Viewed", props)
    }

    fun trackProductAddedToCart(
        productId: String,
        productName: String,
        quantity: Int = 1,
        productPrice: Double? = null,
        productCurrency: String? = null,
        source: String? = null,
        componentId: String? = null,
    ) {
        val vendorOn = configuration.enabled && configuration.trackProductEvents
        val vioOn = configuration.sendToVio && configuration.trackProductEvents
        if (!vendorOn && !vioOn) return
        if (vioOn) {
            VioAnalyticsClient.track(
                name = "add_to_cart",
                context = contractContext(
                    componentId, null,
                    CampaignManager.shared.currentCampaign.value?.id,
                ),
                commerce = VioAnalyticsClient.Commerce(
                    items = listOf(
                        VioAnalyticsClient.Item(
                            productId = productId, name = productName,
                            price = productPrice, quantity = quantity,
                        ),
                    ),
                    value = productPrice?.times(quantity),
                    currency = productCurrency,
                ),
            )
        }
        if (!vendorOn) return
        val props = mutableMapOf<String, Any?>(
            "product_id" to productId,
            "product_name" to productName,
            "quantity" to quantity,
        )
        if (productPrice != null) {
            props["product_price"] = productPrice
            props["revenue"] = productPrice * quantity
        }
        props.putIfNotNull("currency", productCurrency)
        props.putIfNotNull("source", source)
        props.putIfNotNull("component_id", componentId)
        track("Product Added to Cart", props)
    }

    fun trackCheckoutStarted(
        checkoutId: String,
        cartValue: Double,
        currency: String,
        productCount: Int,
        userEmail: String? = null,
        userFirstName: String? = null,
        userLastName: String? = null,
        userId: String? = null,
    ) {
        val vendorOn = configuration.enabled && configuration.trackTransactions
        val vioOn = configuration.sendToVio && configuration.trackTransactions
        if (!vendorOn && !vioOn) return
        if (vioOn) {
            VioAnalyticsClient.track(
                name = "begin_checkout",
                context = contractContext(null, null, CampaignManager.shared.currentCampaign.value?.id),
                commerce = VioAnalyticsClient.Commerce(value = cartValue, currency = currency),
                props = kotlinx.serialization.json.buildJsonObject {
                    put("checkout_id", kotlinx.serialization.json.JsonPrimitive(checkoutId))
                    put("product_count", kotlinx.serialization.json.JsonPrimitive(productCount))
                },
            )
        }
        if (!vendorOn) return

        val normalizedEmail = userEmail?.takeIf { it.isNotBlank() }
        if (!normalizedEmail.isNullOrBlank()) {
            lastAutoIdentifiedEmail = normalizedEmail
            identify(normalizedEmail)
            lastAutoIdentifiedEmail = null
            val userProperties = mutableMapOf<String, Any?>(
                "email" to normalizedEmail,
                "last_checkout_date" to System.currentTimeMillis() / 1000.0,
            )
            var fullName: String? = null
            userFirstName?.takeIf { it.isNotBlank() }?.let {
                userProperties["\$first_name"] = it
                fullName = it
            }
            userLastName?.takeIf { it.isNotBlank() }?.let {
                userProperties["\$last_name"] = it
                fullName = if (fullName != null) "$fullName $it" else it
            }
            fullName?.takeIf { it.isNotBlank() }?.let {
                userProperties["\$name"] = it
            }
            setUserProperties(userProperties)
        } else if (!userId.isNullOrBlank()) {
            identify(userId)
        }

        val props = mutableMapOf<String, Any?>(
            "checkout_id" to checkoutId,
            "cart_value" to cartValue,
            "currency" to currency,
            "product_count" to productCount,
        )
        props.putIfNotNull("user_email", normalizedEmail)
        props.putIfNotNull("user_first_name", userFirstName?.takeIf { it.isNotBlank() })
        props.putIfNotNull("user_last_name", userLastName?.takeIf { it.isNotBlank() })
        track("Checkout Started", props)
    }

    fun trackTransaction(
        checkoutId: String,
        transactionId: String? = null,
        revenue: Double,
        currency: String,
        paymentMethod: String,
        products: List<Map<String, Any?>>,
        discount: Double? = null,
        shipping: Double? = null,
        tax: Double? = null,
    ) {
        val vendorOn = configuration.enabled && configuration.trackTransactions
        val vioOn = configuration.sendToVio && configuration.trackTransactions
        if (!vendorOn && !vioOn) return
        if (vioOn) {
            val contractItems = products.mapNotNull { product ->
                val id = product["product_id"] ?: product["id"] ?: return@mapNotNull null
                VioAnalyticsClient.Item(
                    productId = id.toString(),
                    name = (product["name"] ?: product["product_name"]) as? String,
                    price = (product["price"] as? Number)?.toDouble(),
                    quantity = (product["quantity"] as? Number)?.toInt(),
                )
            }
            VioAnalyticsClient.track(
                name = "purchase",
                context = contractContext(null, null, CampaignManager.shared.currentCampaign.value?.id),
                commerce = VioAnalyticsClient.Commerce(
                    items = contractItems.ifEmpty { null },
                    value = revenue,
                    currency = currency,
                    orderId = transactionId ?: checkoutId,
                    paymentMethod = paymentMethod,
                ),
            )
        }
        if (!vendorOn) return
        val sanitizedProducts = products.map { sanitizeProperties(it) }
        val props = mutableMapOf<String, Any?>(
            "checkout_id" to checkoutId,
            "revenue" to revenue,
            "currency" to currency,
            "payment_method" to paymentMethod,
            "product_count" to products.size,
            "products" to sanitizedProducts,
        )
        props.putIfNotNull("transaction_id", transactionId)
        props.putIfNotNull("discount", discount)
        props.putIfNotNull("shipping", shipping)
        props.putIfNotNull("tax", tax)
        track("Checkout Completed", props)
        mixpanelClient?.let { client ->
            val appendMeta = mapOf(
                "checkout_id" to checkoutId,
                "payment_method" to paymentMethod,
            )
            scope.launch {
                client.appendTransaction(
                    distinctId = currentDistinctId(),
                    amount = revenue,
                    currency = currency,
                    metadata = appendMeta,
                )
            }
        }
    }

    fun track(eventName: String, properties: Map<String, Any?>? = null) {
        if (!configuration.enabled) return
        val finalProps = mutableMapOf<String, Any?>(
            "session_id" to sessionId,
            "timestamp" to System.currentTimeMillis() / 1000.0,
            "sdk_version" to SDK_VERSION,
            "sdk_platform" to SDK_PLATFORM,
        )
        sanitizeProperties(properties).forEach { (key, value) ->
            finalProps[key] = value
        }
        val sanitized = finalProps.filterValues { it != null }
        VioLogger.debug("event=$eventName props=$sanitized", COMPONENT)
        sinks.forEach { sink ->
            try {
                sink.onEvent(eventName, sanitized)
            } catch (t: Throwable) {
                VioLogger.error("Sink failure: ${t.message}", COMPONENT)
            }
        }
        submitToMixpanel(eventName, sanitized)
    }

    private fun submitToMixpanel(eventName: String, properties: Map<String, Any?>) {
        val client = mixpanelClient ?: return
        val token = configuration.mixpanelToken ?: return
        scope.launch {
            val props = properties.toMutableMap()
            if (!props.containsKey("session_id")) {
                props["session_id"] = sessionId
            }
            props["token"] = token
            props["distinct_id"] = currentDistinctId()
            props["time"] = System.currentTimeMillis() / 1000
            client.track(eventName, props)
        }
    }

    private fun currentDistinctId(): String = distinctId ?: sessionId

    fun endImpression(componentId: String, componentType: String) {
        val startTime = impressionTimers.remove(componentId) ?: return
        val durationSeconds = (System.currentTimeMillis() - startTime) / 1000.0
        if (durationSeconds >= 1.0) {
            trackComponentImpression(componentId, componentType, durationSeconds)
        }
    }

    fun flush() {
        if (mixpanelClient != null) {
            VioLogger.debug("Flush requested – HTTP client sends immediately", COMPONENT)
        }
    }

    private fun formatComponentEventName(componentType: String, suffix: String): String {
        val formatted = componentType.split('_').joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
        return "$formatted $suffix"
    }

    private fun MutableMap<String, Any?>.putIfNotNull(key: String, value: Any?) {
        if (value != null) {
            this[key] = value
        }
    }

    private fun sanitizeProperties(properties: Map<String, Any?>?): Map<String, Any?> {
        if (properties == null || properties.isEmpty()) return emptyMap()
        val sanitized = mutableMapOf<String, Any?>()
        properties.forEach { (key, value) ->
            val sanitizedValue = coerceAnalyticsValue(value)
            if (sanitizedValue != null) {
                sanitized[key] = sanitizedValue
            }
        }
        return sanitized
    }

    private fun coerceAnalyticsValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Map<*, *> -> {
            val nested = mutableMapOf<String, Any?>()
            value.forEach { (mapKey, mapValue) ->
                val sanitized = coerceAnalyticsValue(mapValue)
                if (mapKey != null && sanitized != null) {
                    nested[mapKey.toString()] = sanitized
                }
            }
            nested
        }
        is Iterable<*> -> value.mapNotNull { coerceAnalyticsValue(it) }
        is Array<*> -> value.mapNotNull { coerceAnalyticsValue(it) }
        else -> value.toString()
    }
}
