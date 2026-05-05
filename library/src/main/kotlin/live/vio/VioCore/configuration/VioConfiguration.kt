package live.vio.VioCore.configuration

import live.vio.VioCore.analytics.AnalyticsManager
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.models.VioSponsor
import live.vio.VioCore.models.VioPaymentMethod
import live.vio.VioCore.utils.VioLogger
import live.vio.sdk.domain.models.GetAvailableMarketsDto
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kotlin port of the Swift `VioConfiguration`.
 * Provides a singleton with module-level configuration settings for the Vio SDK.
 * Use [configure] to initialize the SDK with your API key and preferred environment
 * before using any Vio UI components or managers.
 */
class VioConfiguration private constructor() {

    // Observable state for Kotlin callers that want to react to configuration changes.
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        val apiKey: String = "",
        val environment: VioEnvironment = VioEnvironment.SANDBOX,
        val theme: VioTheme = VioTheme.defaultTheme(),
        val cart: CartConfiguration = CartConfiguration.default(),
        val network: NetworkConfiguration = NetworkConfiguration.default(),
        val ui: UIConfiguration = UIConfiguration.default(),
        val liveShow: LiveShowConfiguration = LiveShowConfiguration.default(),
        val market: MarketConfiguration = MarketConfiguration.default(),
        val productDetail: ProductDetailConfiguration = ProductDetailConfiguration.default(),
        val localization: LocalizationConfiguration = LocalizationConfiguration.default(),
        val campaign: CampaignConfiguration = CampaignConfiguration.default(),
        val analytics: AnalyticsConfiguration = AnalyticsConfiguration.default(),
        val demoData: DemoDataConfiguration = DemoDataConfiguration.default(),
        val isMarketAvailable: Boolean = true,
        val userCountryCode: String? = null,
        val availableMarkets: List<GetAvailableMarketsDto> = emptyList(),
        val isConfigured: Boolean = false,
        val sponsor: live.vio.VioCore.models.SponsorConfig? = null,
        val commerce: live.vio.VioCore.models.CommerceConfig? = null,
        val checkout: live.vio.VioCore.models.CheckoutConfig? = null,
        val primarySponsor: VioSponsor? = null,
        val secondarySponsors: List<VioSponsor> = emptyList(),
        val sdkBootstrapCommerceApiKey: String? = null,
        val sdkBootstrapCommerceGraphQLURL: String? = null,
        val isRemoteConfigReady: Boolean = false,
        val userId: String? = null,
    )

    companion object {
        val shared: VioConfiguration = VioConfiguration()

        private val isConfiguring = AtomicBoolean(false)

        /**
         * Flag global para activar/desactivar logs del SDK.
         *
         * Atajo sobre [State.network.enableLogging] para alinearse con el API de Swift:
         * `VioConfiguration.shared.loggingEnabled`.
         */
        var loggingEnabled: Boolean
            get() = shared._state.value.network.enableLogging
            set(value) {
                val current = shared._state.value
                shared._state.value = current.copy(
                    network = current.network.copy(enableLogging = value),
                )
            }

        fun configure(
            context: android.content.Context,
            apiKey: String,
            environment: VioEnvironment = VioEnvironment.PRODUCTION,
            theme: VioTheme? = null,
            cartConfig: CartConfiguration? = null,
            networkConfig: NetworkConfiguration? = null,
            uiConfig: UIConfiguration? = null,
            liveShowConfig: LiveShowConfiguration? = null,
            marketConfig: MarketConfiguration? = null,
            productDetailConfig: ProductDetailConfiguration? = null,
            localizationConfig: LocalizationConfiguration? = null,
            campaignConfig: CampaignConfiguration? = null,
            analyticsConfig: AnalyticsConfiguration? = null,
            demoDataConfig: DemoDataConfiguration? = null,
        ) {
            if (!isConfiguring.compareAndSet(false, true)) {
                VioLogger.warning("VioConfiguration.configure() ya está en curso, ignorando llamada concurrente", "VioConfiguration")
                return
            }

            try {
                val instance = shared
                val prevState = instance._state.value
                println("📝 [VioConfiguration] configure starting... isRemoteConfigReady was ${prevState.isRemoteConfigReady}")
                instance._state.value = instance._state.value.copy(
                    apiKey = apiKey,
                    environment = environment,
                    theme = theme ?: VioTheme.defaultTheme(),
                    cart = cartConfig ?: CartConfiguration.default(),
                    network = networkConfig ?: NetworkConfiguration.default(),
                    ui = uiConfig ?: UIConfiguration.default(),
                    liveShow = liveShowConfig ?: LiveShowConfiguration.default(),
                    market = marketConfig ?: MarketConfiguration.default(),
                    productDetail = productDetailConfig ?: ProductDetailConfiguration.default(),
                    localization = localizationConfig ?: LocalizationConfiguration.default(),
                    campaign = campaignConfig ?: CampaignConfiguration.default(),
                    analytics = analyticsConfig ?: AnalyticsConfiguration.default(),
                    demoData = demoDataConfig ?: DemoDataConfiguration.default(),
                    isConfigured = true,
                    isRemoteConfigReady = false,
                )
                live.vio.VioCore.utils.VioContextManager.init(context)
                VioLocalization.configure(instance._state.value.localization)
                AnalyticsManager.configure(instance._state.value.analytics)
                live.vio.VioEngagementSystem.VioEngagementSystem.configure()
                CampaignManager.shared.reinitialize()

                VioLogger.success("Vio SDK configured successfully", "VioConfiguration")
                VioLogger.info("API Key: ${apiKey.take(8)}...", "VioConfiguration")
                VioLogger.info("Environment: $environment", "VioConfiguration")
                VioLogger.info("Theme: ${instance._state.value.theme.name}", "VioConfiguration")
            } finally {
                isConfiguring.set(false)
            }
        }

        /**
         * Aplica una configuración remota del SDK sobre el estado actual.
         *
         * - Actualiza endpoints de campaña (REST y WebSocket).
         * - Actualiza configuración de commerce para `VioCommerceService`.
         * - Re-inicializa `CampaignManager` para que recoja los nuevos endpoints.
         */
        fun applyRemoteConfig(remote: VioRemoteConfig) {
            println("ENTRE applyRemoteConfig")
            val current = shared._state.value

            fun normalizeBase(url: String?): String? {
                if (url == null) return null
                return if (current.environment == VioEnvironment.PRODUCTION && url.startsWith("http://")) {
                    url.replaceFirst("http://", "https://")
                } else url
            }

            val updatedCampaign = current.campaign.copy(
                restAPIBaseURL = normalizeBase(remote.endpoints?.restBase) ?: current.campaign.restAPIBaseURL,
                webSocketBaseURL = normalizeBase(remote.endpoints?.webSocketBase) ?: current.campaign.webSocketBaseURL,
                channelId = remote.clientApp?.id ?: current.campaign.channelId,
                autoDiscover = current.campaign.autoDiscover,
            )

            val remoteCampaignId = remote.campaign?.campaignId ?: remote.campaign?.id
            val updatedLiveShow = current.liveShow.copy(
                campaignId = remoteCampaignId ?: current.liveShow.campaignId,
            )

            val updatedCommerce = remote.commerce?.let {
                live.vio.VioCore.models.CommerceConfig(
                    enabled = !it.apiKey.isNullOrBlank(),
                    apiKey = it.apiKey,
                    endpoint = it.endpoint ?: remote.endpoints?.commerceGraphQL,
                    channelId = it.channelId,
                )
            } ?: current.commerce
            println("**** remote.campaign?.paymentMethods ${remote.campaign?.paymentMethods}");
            // Si el backend expone métodos de pago activos a nivel campaña, los normalizamos a CheckoutConfig
            // para que la UI pueda habilitar/deshabilitar botones correctamente.
            val updatedCheckout = remote.campaign?.paymentMethods
                ?.map { VioPaymentMethod.fromString(it) }
                ?.filter { it != VioPaymentMethod.UNKNOWN }
                ?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?.let { live.vio.VioCore.models.CheckoutConfig(paymentMethods = it) }
                ?: current.checkout

            shared._state.value = current.copy(
                campaign = updatedCampaign,
                commerce = updatedCommerce,
                liveShow = updatedLiveShow,
                checkout = updatedCheckout,
            )

            updatedCommerce?.let { updateCommerce(it) }
            if (updatedCheckout != null && updatedCheckout != current.checkout) {
                updateCheckoutConfig(updatedCheckout)
            }
            CampaignManager.shared.reinitialize()
        }

        fun sponsor(withId: Int): VioSponsor? {
            val current = shared._state.value
            val primary = current.primarySponsor
            if (primary?.id == withId) return primary
            return current.secondarySponsors.firstOrNull { it.id == withId }
        }

        fun commerce(forSponsorId: Int): VioSponsor.CommerceBlock? =
            sponsor(withId = forSponsorId)?.commerce

        fun applySdkBootstrapSponsors(primary: VioSponsor?, secondaries: List<VioSponsor>) {
            val current = shared._state.value
            shared._state.value = current.copy(
                primarySponsor = primary,
                secondarySponsors = secondaries,
            )

            // If primary sponsor has commerce payment methods, apply as checkout defaults.
            val methods = primary?.commerce?.paymentMethods
                ?.map { VioPaymentMethod.fromString(it) }
                ?.filter { it != VioPaymentMethod.UNKNOWN }
                ?.distinct()
                .orEmpty()
            if (methods.isNotEmpty()) {
                updateCheckoutConfig(live.vio.VioCore.models.CheckoutConfig(paymentMethods = methods))
            }
        }

        fun registerComponentSponsor(
            sponsorId: Int,
            sponsorConfig: live.vio.VioCore.models.SponsorConfig?,
            commerceConfig: live.vio.VioCore.models.CommerceConfig?
        ) {
            val current = shared._state.value
            if (current.primarySponsor?.id == sponsorId) return
            
            val existing = current.secondarySponsors.firstOrNull { it.id == sponsorId }
            val newSponsor = existing?.copy(
                name = sponsorConfig?.name ?: existing.name,
                avatarUrl = sponsorConfig?.avatarUrl ?: existing.avatarUrl,
                logoUrl = sponsorConfig?.logoUrl ?: existing.logoUrl,
                primaryColor = sponsorConfig?.primaryColor ?: existing.primaryColor,
                secondaryColor = sponsorConfig?.secondaryColor ?: existing.secondaryColor,
                commerce = commerceConfig?.let {
                    VioSponsor.CommerceBlock(
                        apiKey = it.apiKey ?: existing.commerce?.apiKey ?: "",
                        channelId = it.channelId?.toString() ?: existing.commerce?.channelId
                    )
                } ?: existing.commerce
            ) ?: VioSponsor(
                id = sponsorId,
                name = sponsorConfig?.name ?: "Sponsor $sponsorId",
                avatarUrl = sponsorConfig?.avatarUrl,
                logoUrl = sponsorConfig?.logoUrl,
                primaryColor = sponsorConfig?.primaryColor,
                secondaryColor = sponsorConfig?.secondaryColor,
                commerce = commerceConfig?.apiKey?.let {
                    VioSponsor.CommerceBlock(apiKey = it, channelId = commerceConfig.channelId?.toString())
                }
            )

            val updatedSponsors = current.secondarySponsors.filter { it.id != sponsorId } + newSponsor
            shared._state.value = current.copy(secondarySponsors = updatedSponsors)
            
            // Also notify CommerceSdkClientProvider maybe or it will pick it up on next client() call
        }

        fun applySdkBootstrapCommerce(apiKey: String?, graphQLURL: String?) {
            val current = shared._state.value
            shared._state.value = current.copy(
                sdkBootstrapCommerceApiKey = apiKey?.takeIf { it.isNotBlank() },
                sdkBootstrapCommerceGraphQLURL = graphQLURL?.takeIf { it.isNotBlank() },
                commerce = current.commerce?.copy(
                    enabled = !apiKey.isNullOrBlank(),
                    apiKey = apiKey,
                    endpoint = graphQLURL ?: current.commerce?.endpoint,
                ) ?: live.vio.VioCore.models.CommerceConfig(
                    enabled = !apiKey.isNullOrBlank(),
                    apiKey = apiKey,
                    endpoint = graphQLURL,
                    channelId = null,
                ),
            )

            shared._state.value.commerce?.let { updateCommerce(it) }
        }

        fun markRemoteConfigReady() {
            println("✅ [VioConfiguration] markRemoteConfigReady called")
            val current = shared._state.value
            if (!current.isRemoteConfigReady) {
                shared._state.value = current.copy(isRemoteConfigReady = true)
            }
        }

        suspend fun waitForRemoteConfig(tag: String = "Global", timeoutMillis: Long = 5000) {
            if (shared.state.value.isRemoteConfigReady) return

            println("⏳ [$tag] waitForRemoteConfig: waiting for remote configuration...")
            val start = System.currentTimeMillis()
            while (!shared.state.value.isRemoteConfigReady && (System.currentTimeMillis() - start) < timeoutMillis) {
                delay(10)
            }
            val duration = System.currentTimeMillis() - start
            if (duration >= timeoutMillis) {
                println("⚠️ [$tag] waitForRemoteConfig timed out after ${timeoutMillis}ms")
            } else {
                println("✅ [$tag] waitForRemoteConfig: ready after ${duration}ms")
            }
        }

        fun configure(context: android.content.Context, apiKey: String) {
            configure(
                context = context,
                apiKey = apiKey,
                environment = VioEnvironment.PRODUCTION,
            )
        }

        fun setUserId(userId: String?) {
            shared._state.value = shared._state.value.copy(userId = userId)
            VioLogger.info("User ID set to: $userId", "VioConfiguration")
        }

        fun updateTheme(theme: VioTheme) {
            shared._state.value = shared._state.value.copy(theme = theme)
        }

        fun updateCartConfiguration(config: CartConfiguration) {
            shared._state.value = shared._state.value.copy(cart = config)
        }

        fun updateSponsor(sponsor: live.vio.VioCore.models.SponsorConfig) {
            shared._state.value = shared._state.value.copy(sponsor = sponsor)
        }

        fun updateCommerce(commerce: live.vio.VioCore.models.CommerceConfig) {
            shared._state.value = shared._state.value.copy(commerce = commerce)
            // Clear ProductService cache when commerce config changes
            live.vio.VioUI.Services.ProductService.clearCache()
            // Notify components and integrators
            CampaignManager.shared.notifyCommerceChanged(commerce)
        }

        fun updateCheckoutConfig(checkout: live.vio.VioCore.models.CheckoutConfig) {
            shared._state.value = shared._state.value.copy(checkout = checkout)
            VioLogger.info("Checkout configuration updated with ${checkout.paymentMethods.size} methods: ${checkout.paymentMethods.map { it.name }}", "VioConfiguration")
            println("**** [VioConfiguration] Checkout methods = ${checkout.paymentMethods.map { it.name }}")
        }

        /**
         * Alias para updateCommerce() usado por el backend para actualizaciones dinámicas.
         */
        fun updateDynamicCommerceConfig(commerce: live.vio.VioCore.models.CommerceConfig) {
            updateCommerce(commerce)
        }

        fun setMarketAvailability(
            available: Boolean,
            userCountryCode: String? = null,
            availableMarkets: List<GetAvailableMarketsDto> = emptyList(),
        ) {
            val instance = shared
            val lang = languageCodeForCountry(userCountryCode)
            val localization = instance._state.value.localization
            val hasTranslation = localization.translations.containsKey(lang)
            VioLocalization.setLanguage(if (hasTranslation) lang else localization.defaultLanguage)
            instance._state.value = instance._state.value.copy(
                isMarketAvailable = available,
                userCountryCode = userCountryCode,
                availableMarkets = availableMarkets,
            )
        }

        fun setMarketAvailable(
            available: Boolean,
            userCountryCode: String? = null,
            availableMarkets: List<GetAvailableMarketsDto> = emptyList(),
        ) {
            setMarketAvailability(available, userCountryCode, availableMarkets)
        }

        private fun languageCodeForCountry(code: String?): String {
            val map = mapOf(
                "DE" to "de", "AT" to "de", "CH" to "de",
                "US" to "en", "GB" to "en", "CA" to "en", "AU" to "en",
                "NO" to "no", "SE" to "sv", "DK" to "da", "FI" to "fi",
                "ES" to "es", "FR" to "fr", "IT" to "it", "NL" to "nl",
                "PL" to "pl", "PT" to "pt", "BR" to "pt",
                "MX" to "es", "AR" to "es", "CL" to "es", "CO" to "es",
                "JP" to "ja", "CN" to "zh", "KR" to "ko",
            )
            val normalized = code?.uppercase() ?: return "en"
            return map[normalized] ?: "en"
        }
    }

    fun isValidConfiguration(): Boolean {
        val current = _state.value
        return current.isConfigured && current.apiKey.isNotBlank()
    }

    fun validateConfiguration() {
        val current = _state.value
        if (!current.isConfigured) throw ConfigurationError.NotConfigured
        if (current.apiKey.isBlank()) throw ConfigurationError.MissingApiKey
    }

    fun isMarketAvailableForCountry(countryCode: String): Boolean {
        val markets = _state.value.availableMarkets
        if (markets.isEmpty()) return true
        return markets.any { it.code.equals(countryCode, ignoreCase = true) }
    }

    fun getMarketInfo(countryCode: String): GetAvailableMarketsDto? {
        val markets = _state.value.availableMarkets
        if (markets.isEmpty()) return null
        val normalized = countryCode.uppercase()
        return markets.firstOrNull { it.code?.uppercase() == normalized }
    }

    val shouldUseSDK: Boolean
        get() {
            val current = _state.value
            return current.isConfigured && current.isMarketAvailable
        }
}

enum class VioEnvironment(val baseUrl: String) {
    DEVELOPMENT("https://graph-ql-dev.vio.live"),
    SANDBOX("https://graph-ql-dev.vio.live"),
    PRODUCTION("https://graph-ql.vio.live");

    val graphQLUrl: String get() = "$baseUrl/graphql"
}

sealed class ConfigurationError(message: String) : IllegalStateException(message) {
    object NotConfigured : ConfigurationError("Vio SDK is not configured. Call VioConfiguration.configure() first.")
    object MissingApiKey : ConfigurationError("API Key is required for Vio SDK configuration.")
    object InvalidEnvironment : ConfigurationError("Invalid environment specified.")
    object InvalidTheme : ConfigurationError("Invalid theme configuration.")
    class FileNotFound(val fileName: String) : ConfigurationError("Configuration file '$fileName' not found.")
    object InvalidJSON : ConfigurationError("Invalid JSON configuration format.")
    object InvalidPlist : ConfigurationError("Invalid Plist configuration format.")
}
