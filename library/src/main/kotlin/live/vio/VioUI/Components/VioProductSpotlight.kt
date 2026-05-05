package live.vio.VioUI.Components

import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.models.ProductSpotlightConfig
import live.vio.VioUI.Managers.Product
import live.vio.VioUI.Services.ProductService
import live.vio.VioUI.Services.ProductServiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class VioProductSpotlightState(
    val product: Product? = null,
    val highlightText: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isVisible: Boolean = false,
)

class VioProductSpotlight(
    private val componentId: String? = null,
    private val locationId: String? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val campaignManager: CampaignManager = CampaignManager.shared,
    private val productService: ProductService = ProductService,
) {

    private val _state = MutableStateFlow(VioProductSpotlightState())
    val state: StateFlow<VioProductSpotlightState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var latestConfig: ProductSpotlightConfig? = null

    init {
        scope.launch {
            campaignManager.activeComponents.collectLatest { components ->
                val component = components.findComponent("product_spotlight", locationId, componentId)
                if (component == null || !VioConfiguration.shared.shouldUseSDK) {
                    hide()
                    return@collectLatest
                }
                val config = component.decodeConfig<ProductSpotlightConfig>()
                if (config == null) {
                    hide()
                    return@collectLatest
                }
                loadProduct(config)
            }
        }
    }

    private fun loadProduct(config: ProductSpotlightConfig) {
        latestConfig = config
        val productId = config.productId.toIntOrNull()
        if (productId == null) {
            hide()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val market = VioConfiguration.shared.state.value.market
            try {
                val product = productService.loadProduct(productId, market.currencyCode, market.countryCode)
                _state.value = VioProductSpotlightState(
                    product = product,
                    highlightText = config.highlightText,
                    isLoading = false,
                    isVisible = true,
                )
            } catch (error: ProductServiceError) {
                _state.value = VioProductSpotlightState(
                    product = null,
                    highlightText = config.highlightText,
                    isLoading = false,
                    errorMessage = error.message,
                    isVisible = error !is ProductServiceError.ProductNotFound,
                )
            }
        }
    }

    private fun hide() {
        loadJob?.cancel()
        _state.value = VioProductSpotlightState(isVisible = false)
        latestConfig = null
    }

    fun refresh() {
        val config = latestConfig ?: return
        loadProduct(config)
    }
}
