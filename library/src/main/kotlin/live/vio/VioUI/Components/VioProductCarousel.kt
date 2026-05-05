package live.vio.VioUI.Components

import live.vio.VioCore.analytics.AnalyticsManager
import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.models.Component
import live.vio.VioCore.managers.CampaignNotification
import live.vio.VioCore.models.ProductCarouselConfig
import live.vio.VioUI.Managers.Product
import live.vio.VioUI.Services.ProductService
import live.vio.VioUI.Services.ProductServiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class VioProductCarouselState(
    val products: List<Product> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isVisible: Boolean = false,
    val autoPlay: Boolean = true,
    val intervalMillis: Long = 3_000,
    val layout: ProductCarouselLayout = ProductCarouselLayout.FULL,
    val componentId: String? = null,
    val componentName: String? = null,
    val campaignId: Int? = null,
    val hasProductIds: Boolean = false,
)

enum class ProductCarouselLayout {
    COMPACT,
    FULL,
    HORIZONTAL;

    companion object {
        fun fromConfig(value: String?): ProductCarouselLayout = when (value?.lowercase()) {
            "compact" -> COMPACT
            "horizontal" -> HORIZONTAL
            else -> FULL
        }
    }
}

class VioProductCarousel(
    private val componentId: String? = null,
    private val locationId: String? = null,
    private val layoutOverride: ProductCarouselLayout? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val campaignManager: CampaignManager = CampaignManager.shared,
    private val productService: ProductService = ProductService,
) {
    private val _state = MutableStateFlow(VioProductCarouselState())
    val state: StateFlow<VioProductCarouselState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var latestProductIds: List<Int> = emptyList()
    private var currentComponent: Component? = null
    private val lastTrackedViewSignature = AtomicReference<String?>(null)

    init {
        scope.launch {
            campaignManager.activeComponents.collectLatest { components ->
                val component = components.findComponent("product_carousel", locationId, componentId)
                if (component == null || !VioConfiguration.shared.shouldUseSDK) {
                    hide()
                    return@collectLatest
                }
                val config = component.decodeConfig<ProductCarouselConfig>()
                if (config == null) {
                    hide()
                    return@collectLatest
                }
                handleConfig(component, config)
            }
        }
        scope.launch {
            campaignManager.events.collect { event ->
                if (event is CampaignNotification.CommerceConfigChanged) {
                    refresh()
                }
            }
        }
    }

    private fun handleConfig(component: Component, config: ProductCarouselConfig) {
        val ids = config.productIds.mapNotNull { it.toIntOrNull() }
        println("🎡 [VioProductCarousel] handleConfig: IDs=$ids, name=${component.name}")
        latestProductIds = ids
        currentComponent = component
        val layout = layoutOverride ?: ProductCarouselLayout.fromConfig(config.layout)
        val intervalMillis = config.interval.takeIf { it > 0 }?.toLong() ?: 3_000L
        _state.value = _state.value.copy(
            autoPlay = config.autoPlay,
            intervalMillis = intervalMillis,
            layout = layout,
            isVisible = true,
            componentId = component.id,
            componentName = component.name,
            campaignId = campaignManager.currentCampaign.value?.id,
            hasProductIds = ids.isNotEmpty(),
        )
        val idsOrNull = ids.takeIf { it.isNotEmpty() }
        latestProductIds = ids
        println("🎡 [VioProductCarousel] triggering load with ${idsOrNull?.size ?: "ALL"} IDs")
        triggerLoad(idsOrNull)
    }

    private fun triggerLoad(productIds: List<Int>?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val market = VioConfiguration.shared.state.value.market
            try {
                val products = productService.loadProducts(
                    productIds = productIds, 
                    currency = market.currencyCode, 
                    country = market.countryCode,
                    sponsorId = currentComponent?.sponsorId
                )
                println("🎡 [VioProductCarousel] load success: got ${products.size} products. IDs=${products.map { it.id }}")
                _state.value = _state.value.copy(
                    products = products,
                    isLoading = false,
                    errorMessage = null,
                    isVisible = true,
                )
                trackComponentView(products.size)
            } catch (error: ProductServiceError) {
                println("🎡 [VioProductCarousel] load error: ${error.message}")
                _state.value = _state.value.copy(
                    products = emptyList(),
                    isLoading = false,
                    errorMessage = error.message,
                    isVisible = error !is ProductServiceError.ProductNotFound,
                )
            }
        }
    }

    private fun hide() {
        loadJob?.cancel()
        _state.value = VioProductCarouselState(isVisible = false)
        latestProductIds = emptyList()
        currentComponent = null
        lastTrackedViewSignature.set(null)
    }

    fun refresh() {
        if (latestProductIds.isNotEmpty()) {
            triggerLoad(latestProductIds)
        }
    }

    fun setProducts(products: List<Product>) {
        _state.value = _state.value.copy(
            products = products,
            isVisible = true,
            isLoading = false
        )
    }

    private fun trackComponentView(productCount: Int) {
        val component = currentComponent ?: return
        val snapshot = _state.value
        val signature = buildString {
            append(component.id)
            append("|")
            append(productCount)
            append("|")
            append(snapshot.layout)
            append("|")
            append(snapshot.autoPlay)
            append("|")
            append(snapshot.hasProductIds)
        }
        if (lastTrackedViewSignature.get() == signature) return
        lastTrackedViewSignature.set(signature)
        AnalyticsManager.trackComponentView(
            componentId = component.id,
            componentType = "product_carousel",
            componentName = component.name,
            campaignId = snapshot.campaignId,
            metadata = mapOf(
                "layout" to snapshot.layout.name.lowercase(),
                "product_count" to productCount,
                "has_product_ids" to snapshot.hasProductIds,
                "auto_play" to snapshot.autoPlay,
            ),
        )
    }
}
