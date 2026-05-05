package live.vio.VioUI.Components

import live.vio.VioCore.analytics.AnalyticsManager
import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.models.Component
import live.vio.VioCore.managers.CampaignNotification
import live.vio.VioCore.models.ProductStoreConfig
import live.vio.VioCore.utils.VioLogger
import live.vio.VioUI.Managers.Product
import live.vio.VioUI.Services.ProductService
import live.vio.VioUI.Services.ProductServiceError
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class VioProductStoreState(
    val products: List<Product> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isVisible: Boolean = false,
    val displayType: ProductStoreDisplayType = ProductStoreDisplayType.GRID,
    val columns: Int = 2,
    val mode: String = "all",
    val productIds: List<Int>? = null,
    val isMarketUnavailable: Boolean = false,
)

enum class ProductStoreDisplayType { GRID, LIST }

class VioProductStore(
    private val componentId: String? = null,
    private val locationId: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val campaignManager: CampaignManager = CampaignManager.shared,
    private val productService: ProductService = ProductService,
) {

    companion object {
        private const val COMPONENT_TYPE = "product_store"
        private const val LOGGER_TAG = "VioProductStore"
    }

    private val _state = MutableStateFlow(VioProductStoreState())
    val state: StateFlow<VioProductStoreState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val lastConfigId = AtomicReference<String?>(null)
    private var lastProductIds: List<Int>? = null
    private var lastMode: String = "all"
    private var lastSponsorId: Int? = null

    init {
        scope.launch {
            campaignManager.activeComponents.collectLatest { components ->
                val component = components.findComponent(COMPONENT_TYPE, locationId, componentId)
                if (component == null || !VioConfiguration.shared.shouldUseSDK) {
                    hide()
                    return@collectLatest
                }
                val config = component.decodeConfig<ProductStoreConfig>()
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

    private fun handleConfig(component: Component, config: ProductStoreConfig) {
        val configIdentifier = buildConfigId(config)
        if (lastConfigId.get() == configIdentifier && state.value.isVisible) {
            return
        }

        lastConfigId.set(configIdentifier)
        val displayType = if (config.displayType.equals("list", ignoreCase = true)) {
            ProductStoreDisplayType.LIST
        } else {
            ProductStoreDisplayType.GRID
        }
        val columns = config.columns.coerceAtLeast(1)
        val ids = config.productIds
            ?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
        val normalizedMode = if (config.mode.equals("filtered", ignoreCase = true)) {
            "filtered"
        } else {
            "all"
        }

        _state.value = _state.value.copy(
            isVisible = true,
            displayType = displayType,
            columns = columns,
            mode = normalizedMode,
            productIds = ids,
            isMarketUnavailable = false,
        )
        lastProductIds = ids
        lastMode = normalizedMode
        lastSponsorId = component.sponsorId
        trackComponentView(component, config)
        triggerLoad(normalizedMode, ids, component.sponsorId)
    }

    private fun triggerLoad(mode: String, productIds: List<Int>?, sponsorId: Int?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null, isMarketUnavailable = false)
            if (!VioConfiguration.shared.shouldUseSDK) {
                _state.value = _state.value.copy(
                    products = emptyList(),
                    isLoading = false,
                    isVisible = false,
                    isMarketUnavailable = true,
                )
                return@launch
            }

            val market = VioConfiguration.shared.state.value.market
            val currency = market.currencyCode
            val country = market.countryCode
            val filteredMode = mode.equals("filtered", ignoreCase = true)
            val hasValidIds = !productIds.isNullOrEmpty()
            val idsToUse = if (filteredMode && hasValidIds) productIds else null

            VioLogger.debug("Loading products - mode=$mode ids=${idsToUse ?: "all"}", LOGGER_TAG)
            try {
                var products = productService.loadProducts(idsToUse, currency, country, sponsorId = sponsorId)
                if (products.isEmpty() && filteredMode && hasValidIds) {
                    VioLogger.warning(
                        "Filtered mode returned no products, falling back to full load",
                        LOGGER_TAG,
                    )
                    products = productService.loadProducts(null, currency, country, sponsorId = sponsorId)
                }

                val message = if (filteredMode && !hasValidIds && products.isEmpty()) {
                    "No valid product IDs"
                } else {
                    null
                }

                _state.value = _state.value.copy(
                    products = products,
                    isLoading = false,
                    errorMessage = message,
                    isVisible = true,
                    isMarketUnavailable = false,
                )
            } catch (error: ProductServiceError) {
                handleLoadError(error)
            }
        }
    }

    private fun handleLoadError(error: ProductServiceError) {
        val (message, hideComponent, marketUnavailable) = when (error) {
            is ProductServiceError.InvalidConfiguration -> Triple(error.message, false, false)
            is ProductServiceError.InvalidProductId -> Triple(error.message, false, false)
            is ProductServiceError.ProductNotFound -> Triple(error.message, false, false)
            is ProductServiceError.Network -> Triple(error.message ?: "Network error", false, false)
            is ProductServiceError.Sdk -> {
                val sdk = error.error
                val unavailable = sdk.code.equals("NOT_FOUND", ignoreCase = true) || sdk.status == 404
                Triple(sdk.messageText, unavailable, unavailable)
            }
        }
        VioLogger.error("Failed to load products: ${message ?: "unknown error"}", LOGGER_TAG)
        _state.value = _state.value.copy(
            products = emptyList(),
            isLoading = false,
            errorMessage = if (marketUnavailable) null else message,
            isVisible = !hideComponent,
            isMarketUnavailable = marketUnavailable,
        )
    }

    private fun hide() {
        loadJob?.cancel()
        _state.value = VioProductStoreState(isVisible = false)
        lastConfigId.set(null)
        lastProductIds = null
        lastMode = "all"
        lastSponsorId = null
    }

    private fun buildConfigId(config: ProductStoreConfig): String {
        val ids = config.productIds?.joinToString("-") ?: "all"
        return "${config.mode}-$ids-${config.displayType}-${config.columns}"
    }

    private fun trackComponentView(component: Component, config: ProductStoreConfig) {
        AnalyticsManager.trackComponentView(
            componentId = component.id,
            componentType = COMPONENT_TYPE,
            componentName = component.name,
            campaignId = campaignManager.currentCampaign.value?.id,
            metadata = mapOf(
                "display_type" to config.displayType,
                "columns" to config.columns,
                "product_count" to _state.value.products.size,
                "has_product_ids" to (config.productIds?.isNotEmpty() == true),
            ),
        )
    }

    fun setProducts(products: List<Product>) {
        _state.value = _state.value.copy(
            products = products,
            isVisible = true,
            isLoading = false,
            errorMessage = null,
            isMarketUnavailable = false
        )
    }

    fun refresh() {
        if (!state.value.isVisible) return
        triggerLoad(lastMode, lastProductIds, lastSponsorId)
    }
}


