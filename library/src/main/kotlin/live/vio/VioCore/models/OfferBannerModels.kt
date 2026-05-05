package live.vio.VioCore.models

import live.vio.VioCore.managers.CampaignManager
import live.vio.sdk.core.helpers.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Configuration for Offer Banner component
data class OfferBannerConfig(
    val logoUrl: String,
    val title: String,
    val subtitle: String? = null,
    val backgroundImageUrl: String? = null,
    val backgroundColor: String? = null,
    val countdownEndDate: String,
    val discountBadgeText: String,
    val ctaText: String,
    val ctaLink: String? = null,
    val overlayOpacity: Double? = null,
    val buttonColor: String? = null,
    val deeplinkUrl: String? = null,
    val deeplinkAction: String? = null,
)

// Simple banner config
data class BannerConfig(
    val imageUrl: String,
    val title: String,
    val subtitle: String? = null,
    val ctaText: String? = null,
    val ctaLink: String? = null,
    val deeplinkUrl: String? = null,
    val deeplinkAction: String? = null,
)

// Product spotlight config
data class ProductSpotlightConfig(
    val productId: String,
    val highlightText: String? = null,
)

data class ProductBannerConfig(
    val productId: String,
    val backgroundImageUrl: String,
    val title: String,
    val subtitle: String? = null,
    val ctaText: String,
    val ctaLink: String? = null,
    val deeplink: String? = null,
    val titleColor: String? = null,
    val subtitleColor: String? = null,
    val buttonBackgroundColor: String? = null,
    val buttonTextColor: String? = null,
    val backgroundColor: String? = null,
    val overlayOpacity: Double? = null,
    val bannerHeight: Int? = null,
    val bannerHeightRatio: Double? = null,
    val titleFontSize: Int? = null,
    val subtitleFontSize: Int? = null,
    val buttonFontSize: Int? = null,
    val textAlignment: String? = null,
    val contentVerticalAlignment: String? = null,
)

data class ProductCarouselConfig(
    val productIds: List<String> = emptyList(),
    val autoPlay: Boolean = false,
    val interval: Int = 3_000,
    val layout: String? = null,
)

data class ProductStoreConfig(
    val mode: String,
    val productIds: List<String>? = null,
    val displayType: String = "grid",
    val columns: Int = 2,
)

// Countdown config
data class CountdownConfig(
    val endDate: String,
    val title: String,
    val logoUrl: String? = null,
    val subtitle: String? = null,
    val backgroundImageUrl: String? = null,
    val backgroundColor: String? = null,
    val discountBadgeText: String? = null,
    val ctaText: String? = null,
    val ctaLink: String? = null,
    val deeplink: String? = null,
    val overlayOpacity: Double? = null,
    val buttonColor: String? = null,
    val style: String? = null,
) {
    fun toOfferBannerConfig(): OfferBannerConfig? {
        return OfferBannerConfig(
            logoUrl = logoUrl ?: "",
            title = title,
            subtitle = subtitle,
            backgroundImageUrl = backgroundImageUrl,
            backgroundColor = backgroundColor,
            countdownEndDate = endDate,
            discountBadgeText = discountBadgeText ?: "",
            ctaText = ctaText ?: "Shop Now",
            ctaLink = ctaLink,
            overlayOpacity = overlayOpacity,
            buttonColor = buttonColor,
            deeplinkUrl = deeplink,
            deeplinkAction = null,
        )
    }
}

// Carousel auto config
data class CarouselAutoConfig(
    val channelId: String,
    val displayCount: Int? = null,
)

// Carousel manual config
data class CarouselManualConfig(
    val productIds: List<String>,
)

// Offer badge config
data class OfferBadgeConfig(
    val text: String,
    val color: String? = null,
)

// Dynamic component config (analogy to Swift enum with associated values)
sealed class ComponentConfig {
    data class Banner(val config: BannerConfig) : ComponentConfig()
    data class OfferBanner(val config: OfferBannerConfig) : ComponentConfig()
    data class ProductSpotlight(val config: ProductSpotlightConfig) : ComponentConfig()
    data class Countdown(val config: CountdownConfig) : ComponentConfig()
    data class CarouselAuto(val config: CarouselAutoConfig) : ComponentConfig()
    data class CarouselManual(val config: CarouselManualConfig) : ComponentConfig()
    data class OfferBadge(val config: OfferBadgeConfig) : ComponentConfig()
    data class ProductCarousel(val config: ProductCarouselConfig) : ComponentConfig()
    data class ProductBanner(val config: ProductBannerConfig) : ComponentConfig()
    data class ProductStore(val config: ProductStoreConfig) : ComponentConfig()
}

// Component response model
data class ActiveComponentResponse(
    val componentId: String,
    val type: String,
    val name: String,
    val config: ComponentConfig,
    val status: String,
    val activatedAt: String? = null,
)

// WebSocket-like status message (model only)
data class ComponentStatusMessage(
    val type: String,
    val campaignId: Int,
    val componentId: String,
    val status: String,
    val component: ComponentData? = null,
) {
    data class ComponentData(
        val id: String,
        val type: String,
        val name: String,
        val config: ComponentConfig,
    )
}

// Active component from API (simplified, as in Swift)
data class ActiveComponent(
    val id: String?,
    val type: String,
    val config: OfferBannerConfig? = null,
)

// Manager for campaign components, syncing with CampaignManager
class ComponentManager private constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {
    companion object {
        val shared: ComponentManager by lazy { ComponentManager() }
    }

    private val _activeBanner = MutableStateFlow<OfferBannerConfig?>(null)
    val activeBanner: StateFlow<OfferBannerConfig?> = _activeBanner.asStateFlow()

    private val _activeBannerComponent = MutableStateFlow<live.vio.VioCore.models.Component?>(null)
    val activeBannerComponent: StateFlow<live.vio.VioCore.models.Component?> = _activeBannerComponent.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        scope.launch {
            CampaignManager.shared.activeComponents.collectLatest { components ->
                // Look for offer_banner or countdown components
                val bannerComponent = components.firstOrNull { it.type == "offer_banner" || it.type == "countdown" }
                if (bannerComponent != null) {
                    val config = runCatching {
                        val json = JsonUtils.mapper.writeValueAsString(bannerComponent.config)
                        JsonUtils.mapper.readValue(json, OfferBannerConfig::class.java)
                    }.getOrNull()
                    _activeBanner.value = config
                    _activeBannerComponent.value = bannerComponent
                } else {
                    _activeBanner.value = null
                    _activeBannerComponent.value = null
                }
            }
        }

        scope.launch {
            CampaignManager.shared.isConnected.collectLatest { connected ->
                _isConnected.value = connected
            }
        }
    }

    suspend fun connect() {
        CampaignManager.shared.initializeCampaign()
    }

    fun disconnect() {
        CampaignManager.shared.disconnect()
    }
}
