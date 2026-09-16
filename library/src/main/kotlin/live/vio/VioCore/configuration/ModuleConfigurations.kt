package live.vio.VioCore.configuration

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

// Cart Configuration

data class CartConfiguration(
    val floatingCartPosition: FloatingCartPosition = FloatingCartPosition.BOTTOM_RIGHT,
    val floatingCartDisplayMode: FloatingCartDisplayMode = FloatingCartDisplayMode.MINIMAL,
    val floatingCartSize: FloatingCartSize = FloatingCartSize.SMALL,
    // Demo/testing: show indicator even when cart is empty
    val alwaysShowFloatingCart: Boolean = false,
    val autoSaveCart: Boolean = true,
    val cartPersistenceKey: String = "vio_cart",
    val maxQuantityPerItem: Int = 99,
    val showCartNotifications: Boolean = true,
    val enableGuestCheckout: Boolean = true,
    val requirePhoneNumber: Boolean = true,
    val defaultShippingCountry: String = "NO",
    val supportedPaymentMethods: List<String> = listOf("stripe", "klarna", "paypal"),
    val klarnaMode: KlarnaMode = KlarnaMode.WEB,
) {
    companion object { fun default() = CartConfiguration() }
}

enum class FloatingCartPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }
enum class FloatingCartDisplayMode { FULL, COMPACT, MINIMAL, ICON_ONLY }
enum class FloatingCartSize { SMALL, MEDIUM, LARGE }

// Payment Modes
enum class KlarnaMode { WEB, NATIVE }

// Market Configuration

data class MarketConfiguration(
    val countryCode: String = "NO",
    val countryName: String = "Norway",
    val currencyCode: String = "NOK",
    val currencySymbol: String = "kr",
    val phoneCode: String = "+47",
    val flagURL: String? = "https://flagcdn.com/w320/no.png",
) {
    companion object { fun default() = MarketConfiguration() }
}

// Network Configuration

data class NetworkConfiguration(
    val timeout: Duration = 30.seconds,
    val retryAttempts: Int = 3,
    val enableCaching: Boolean = true,
    val cacheDuration: Duration = 300.seconds,
    val enableQueryBatching: Boolean = true,
    val enableSubscriptions: Boolean = false,
    val maxConcurrentRequests: Int = 6,
    val requestPriority: RequestPriority = RequestPriority.NORMAL,
    val enableCompression: Boolean = true,
    val enableSSLPinning: Boolean = false,
    val trustedHosts: List<String> = emptyList(),
    val enableCertificateValidation: Boolean = true,
    val enableLogging: Boolean = false,
    val logLevel: LogLevel = LogLevel.INFO,
    val enableNetworkInspector: Boolean = false,
    val customHeaders: Map<String, String> = emptyMap(),
    val enableOfflineMode: Boolean = false,
    val offlineCacheDuration: Duration = 24.hours,
    val syncStrategy: SyncStrategy = SyncStrategy.AUTOMATIC,
) {
    companion object { fun default() = NetworkConfiguration() }
}

enum class LogLevel { DEBUG, INFO, WARNING, ERROR }
enum class RequestPriority { LOW, NORMAL, HIGH, CRITICAL }
enum class SyncStrategy { AUTOMATIC, MANUAL, BACKGROUND, REALTIME }

// UI Configuration

data class UIConfiguration(
    val defaultProductCardVariant: ProductCardVariant = ProductCardVariant.GRID,
    val enableProductCardAnimations: Boolean = true,
    val showProductBrands: Boolean = true,
    val showProductDescriptions: Boolean = false,
    val showDiscountBadge: Boolean = false,
    val discountBadgeText: String? = null,
    val defaultSliderLayout: ProductSliderLayout = ProductSliderLayout.CARDS,
    val enableSliderPagination: Boolean = true,
    val maxSliderItems: Int = 20,
    val imageQuality: ImageQuality = ImageQuality.MEDIUM,
    val enableImageCaching: Boolean = true,
    val placeholderImageType: PlaceholderImageType = PlaceholderImageType.SHIMMER,
    val typographyConfig: TypographyConfiguration = TypographyConfiguration.default(),
    val shadowConfig: ShadowConfiguration = ShadowConfiguration.default(),
    val animationConfig: AnimationConfiguration = AnimationConfiguration.default(),
    val layoutConfig: LayoutConfiguration = LayoutConfiguration.default(),
    val accessibilityConfig: AccessibilityConfiguration = AccessibilityConfiguration.default(),
    val enableAnimations: Boolean = true,
    val animationDuration: Double = 0.3,
    val enableHapticFeedback: Boolean = true,
) {
    companion object { fun default() = UIConfiguration() }
}

enum class ProductCardVariant { GRID, LIST, HERO, MINIMAL }
enum class ProductSliderLayout { COMPACT, CARDS, FEATURED, WIDE, SHOWCASE, MICRO }
enum class ImageQuality { LOW, MEDIUM, HIGH }
enum class PlaceholderImageType { SHIMMER, BLURRED, SOLID, NONE }

// Product Detail Configuration (analogy to Swift)

data class ProductDetailConfiguration(
    val modalHeight: ProductDetailModalHeight = ProductDetailModalHeight.FULL,
    val imageFullWidth: Boolean = false,
    val imageCornerRadius: Float = 12f,
    val imageHeight: Float? = null,
    val showImageGallery: Boolean = true,
    val headerStyle: ProductDetailHeaderStyle = ProductDetailHeaderStyle.STANDARD,
    val enableImageZoom: Boolean = true,
    val showNavigationTitle: Boolean = true,
    val closeButtonStyle: CloseButtonStyle = CloseButtonStyle.NAVIGATION_BAR,
    val showDescription: Boolean = true,
    val showSpecifications: Boolean = true,
    val showCloseButton: Boolean = true,
    val dismissOnTapOutside: Boolean = true,
    val enableShareButton: Boolean = false,
) {
    companion object { fun default() = ProductDetailConfiguration() }
}

enum class ProductDetailModalHeight(val fraction: Float) {
    FULL(1.0f), THREE_QUARTERS(0.75f), HALF(0.5f)
}

enum class ProductDetailHeaderStyle { STANDARD, COMPACT }

enum class CloseButtonStyle { NAVIGATION_BAR, OVERLAY_TOP_LEFT, OVERLAY_TOP_RIGHT }

// Typography Configuration

data class TypographyConfiguration(
    val fontFamily: String? = null,
    val enableCustomFonts: Boolean = false,
    val fontWeightMapping: FontWeightMapping = FontWeightMapping.default(),
    val supportDynamicType: Boolean = true,
    val minFontScale: Float = 0.8f,
    val maxFontScale: Float = 1.4f,
    val lineHeightMultiplier: Float = 1.2f,
    val letterSpacing: Float = 0f,
    val textAlignment: TextAlignment = TextAlignment.NATURAL,
) {
    companion object { fun default() = TypographyConfiguration() }
}

enum class TextAlignment { LEADING, CENTER, TRAILING, NATURAL }

data class FontWeightMapping(
    val light: String = "Light",
    val regular: String = "Regular",
    val medium: String = "Medium",
    val semibold: String = "Semibold",
    val bold: String = "Bold",
) {
    companion object { fun default() = FontWeightMapping() }
}

// Shadow & Effects

data class ShadowConfiguration(
    val cardShadowRadius: Float = 4f,
    val cardShadowOpacity: Double = 0.1,
    val cardShadowOffsetX: Float = 0f,
    val cardShadowOffsetY: Float = 2f,
    val cardShadowColor: ShadowColor = ShadowColor.ADAPTIVE,
    val buttonShadowEnabled: Boolean = true,
    val buttonShadowRadius: Float = 2f,
    val buttonShadowOpacity: Double = 0.15,
    val modalShadowRadius: Float = 20f,
    val modalShadowOpacity: Double = 0.3,
    val enableBlurEffects: Boolean = true,
    val blurIntensity: Double = 0.3,
    val blurStyle: BlurStyle = BlurStyle.SYSTEM_MATERIAL,
) {
    companion object { fun default() = ShadowConfiguration() }
}

enum class ShadowColor { BLACK, GRAY, ADAPTIVE, CUSTOM }
enum class BlurStyle { SYSTEM_MATERIAL, REGULAR_MATERIAL, THICK_MATERIAL, THIN_MATERIAL, ULTRA_THIN_MATERIAL }

// Animation Configuration

data class AnimationConfiguration(
    val defaultDuration: Double = 0.3,
    val springResponse: Double = 0.4,
    val springDamping: Double = 0.8,
    val enableSpringAnimations: Boolean = true,
    val enableMicroInteractions: Boolean = true,
    val enablePageTransitions: Boolean = true,
    val enableSharedElementTransitions: Boolean = false,
    val defaultEasing: AnimationEasing = AnimationEasing.EASE_IN_OUT,
    val customTimingCurve: TimingCurve? = null,
    val respectReduceMotion: Boolean = true,
    val animationQuality: AnimationQuality = AnimationQuality.HIGH,
    val enableHardwareAcceleration: Boolean = true,
) {
    companion object { fun default() = AnimationConfiguration() }
}

enum class AnimationEasing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SPRING, CUSTOM }
enum class AnimationQuality { LOW, MEDIUM, HIGH }
data class TimingCurve(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

// Layout Configuration

data class LayoutConfiguration(
    val gridColumns: Int = 2,
    val gridSpacing: Float = 16f,
    val gridMinItemWidth: Float = 150f,
    val gridMaxItemWidth: Float? = null,
    val respectSafeAreas: Boolean = true,
    val customSafeAreaInsets: EdgeInsets? = null,
    val extendThroughSafeArea: Boolean = false,
    val compactWidthThreshold: Float = 768f,
    val regularWidthThreshold: Float = 1024f,
    val enableResponsiveLayout: Boolean = true,
    val screenMargins: Float = 16f,
    val sectionSpacing: Float = 24f,
    val componentSpacing: Float = 16f,
) {
    companion object { fun default() = LayoutConfiguration() }
}
data class EdgeInsets(val top: Float, val leading: Float, val bottom: Float, val trailing: Float)

// Accessibility Configuration

data class AccessibilityConfiguration(
    val enableVoiceOverOptimizations: Boolean = true,
    val customVoiceOverLabels: Map<String, String> = emptyMap(),
    val enableDynamicTypeSupport: Boolean = true,
    val maxDynamicTypeSize: DynamicTypeSize = DynamicTypeSize.ACCESSIBILITY3,
    val respectHighContrastMode: Boolean = true,
    val enableColorBlindnessSupport: Boolean = false,
    val contrastRatio: ContrastRatio = ContrastRatio.AA,
    val respectReduceMotion: Boolean = true,
    val alternativeToAnimations: Boolean = true,
    val minimumTouchTargetSize: Float = 44f,
    val enableHapticFeedback: Boolean = true,
    val hapticIntensity: HapticIntensity = HapticIntensity.MEDIUM,
) {
    companion object { fun default() = AccessibilityConfiguration() }
}

enum class DynamicTypeSize {
    X_SMALL, SMALL, MEDIUM, LARGE, X_LARGE, XX_LARGE, XXX_LARGE,
    ACCESSIBILITY1, ACCESSIBILITY2, ACCESSIBILITY3, ACCESSIBILITY4, ACCESSIBILITY5
}

enum class ContrastRatio { AA, AAA, CUSTOM }
enum class HapticIntensity { LIGHT, MEDIUM, HEAVY }

// LiveShow Configuration

data class LiveShowConfiguration(
    val autoJoinChat: Boolean = true,
    val enableChatModeration: Boolean = true,
    val maxChatMessageLength: Int = 200,
    val enableEmojis: Boolean = true,
    val enableShoppingDuringStream: Boolean = true,
    val showProductOverlays: Boolean = true,
    val enableQuickBuy: Boolean = true,
    val enableStreamNotifications: Boolean = true,
    val enableProductNotifications: Boolean = true,
    val enableChatNotifications: Boolean = false,
    val videoQuality: VideoQuality = VideoQuality.AUTO,
    val enableAutoplay: Boolean = false,
    val enablePictureInPicture: Boolean = true,
    val tipioApiKey: String = "",
    val tipioBaseUrl: String = "https://stg-dev-microservices.tipioapp.com",
    val campaignId: Int = 0,
) {
    companion object { fun default() = LiveShowConfiguration() }
}

enum class VideoQuality { LOW, MEDIUM, HIGH, HD, AUTO }

data class CampaignConfiguration(
    val webSocketBaseURL: String = "https://api-staging.vio.live",
    val restAPIBaseURL: String = "https://api-staging.vio.live",
    /**
     * API key específica para el flujo de contentId (máxima prioridad).
     * Usada por [live.vio.VioEngagementSystem.BroadcastValidationService] al llamar
     * GET /v1/sdk/broadcast. Si es null o en blanco, hace fallback a [campaignAdminApiKey]
     * y luego al API key general del SDK.
     */
    val campaignApiKey: String? = null,
    /**
     * Habilita el descubrimiento automático de campañas basado en el contexto.
     */
    val autoDiscover: Boolean = false,
    /**
     * ID del canal para asociar campañas en modo auto-discovery.
     */
    val channelId: Int? = null,
    /**
     * API key específica para endpoints de administración de campaña.
     * Si es null o en blanco, se debe hacer fallback al API key general del SDK.
     */
    val campaignAdminApiKey: String? = null,
    /**
     * Mapeado desde el flag de Apple Pay para mostrar Google Pay en Android.
     */
    val hasGooglePay: Boolean = true,
) {
    companion object { fun default() = CampaignConfiguration() }
}
