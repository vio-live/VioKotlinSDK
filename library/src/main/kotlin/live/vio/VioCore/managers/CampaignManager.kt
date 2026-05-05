package live.vio.VioCore.managers

import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.configuration.VioSDKConfigService
import live.vio.VioCore.models.Campaign
import live.vio.VioCore.models.CampaignEndedEvent
import live.vio.VioCore.models.CampaignPausedEvent
import live.vio.VioCore.models.CampaignResumedEvent
import live.vio.VioCore.models.CampaignStartedEvent
import live.vio.VioCore.models.CampaignState
import live.vio.VioCore.models.Component
import live.vio.VioCore.models.ComponentConfigUpdatedEvent
import live.vio.VioCore.models.ComponentStatusChangedEvent
import live.vio.VioCore.models.*
import live.vio.VioEngagementSystem.models.Contest
import live.vio.VioEngagementSystem.models.Poll
import live.vio.VioCore.placements.VioPlacementManifestUploader
import live.vio.VioCore.utils.VioLogger
import live.vio.sdk.core.helpers.JsonUtils
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kotlin analogue of the Swift `CampaignManager`.
 */
class CampaignManager private constructor(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    companion object {
        val shared: CampaignManager = CampaignManager()
        private const val COMPONENT = "CampaignManager"
        /**
         * Notificación emitida cuando el logo de la campaña cambia.
         * El payload contiene 'oldLogoUrl' y 'newLogoUrl'.
         */
        const val NOTIFICATION_CAMPAIGN_LOGO_CHANGED = "VioCampaignLogoChanged"
        
        /**
         * Notificación emitida cuando la configuración de commerce cambia.
         */
        const val NOTIFICATION_COMMERCE_CONFIG_CHANGED = "VioCommerceConfigChanged"
    }

    /**
     * Emite una notificación de que la configuración de commerce ha cambiado.
     */
    fun notifyCommerceChanged(commerce: live.vio.VioCore.models.CommerceConfig) {
        scope.launch {
            _events.emit(CampaignNotification.CommerceConfigChanged(commerce))
        }
    }

    /**
     * Helper para emitir notificaciones de cambio de logo.
     */
    private suspend fun emitLogoChanged(oldLogoUrl: String?, newLogoUrl: String?) {
        if (oldLogoUrl != newLogoUrl) {
            VioLogger.info("Campaign logo changed: '$oldLogoUrl' -> '$newLogoUrl'", COMPONENT)
            _events.emit(CampaignNotification.CampaignLogoChanged(oldLogoUrl, newLogoUrl))
            
            // Pre-cache new logo if available
            if (!newLogoUrl.isNullOrBlank()) {
                scope.launch { preCacheLogo(newLogoUrl) }
            }
        }
    }

    private val _isCampaignActive = MutableStateFlow(true)
    val isCampaignActive: StateFlow<Boolean> = _isCampaignActive.asStateFlow()

    private val _campaignState = MutableStateFlow(CampaignState.ACTIVE)
    val campaignState: StateFlow<CampaignState> = _campaignState.asStateFlow()

    private val _activeComponents = MutableStateFlow<List<Component>>(emptyList())
    val activeComponents: StateFlow<List<Component>> = _activeComponents.asStateFlow()

    private var allComponents: List<Component> = emptyList()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _currentCampaign = MutableStateFlow<Campaign?>(null)
    val currentCampaign: StateFlow<Campaign?> = _currentCampaign.asStateFlow()

    // Multi-campaign support for auto-discovery mode
    private val _activeCampaigns = MutableStateFlow<List<Campaign>>(emptyList())
    val activeCampaigns: StateFlow<List<Campaign>> = _activeCampaigns.asStateFlow()

    private val _discoveredCampaigns = MutableStateFlow<List<Campaign>>(emptyList())
    val discoveredCampaigns: StateFlow<List<Campaign>> = _discoveredCampaigns.asStateFlow()

    private val isSettingUpBroadcast = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _events = MutableSharedFlow<CampaignNotification>(extraBufferCapacity = 32)
    val events: SharedFlow<CampaignNotification> = _events.asSharedFlow()

    private val _activeCartIntentEvent = MutableStateFlow<CampaignWebSocketManager.CartIntentEvent?>(null)
    val activeCartIntentEvent: StateFlow<CampaignWebSocketManager.CartIntentEvent?> = _activeCartIntentEvent.asStateFlow()

    private val isInitializing = AtomicBoolean(false)

    private var apiKey: String = ""
    private var restApiBaseUrl: String = ""
    private var webSocketBaseUrl: String = ""
    private var campaignApiKey: String? = null
    private var campaignAdminApiKey: String? = null
    private var campaignId: Int? = null
    private var appBundleId: String? = null
    private var currentMatchId: String? = null
    private var currentMatchContext: MatchContext? = null
    private var webSocketManager: CampaignWebSocketManager? = null

    init {
        applyConfiguration(VioConfiguration.shared.state.value)
    }

    fun reinitialize() {
        disconnect()
        applyConfiguration(VioConfiguration.shared.state.value)
    }

    suspend fun initializeCampaign() {
        VioLogger.debug("[CampaignManager] Initializing campaign")
        if (!isInitializing.compareAndSet(false, true)) {
            VioLogger.debug("[CampaignManager] Campaign initialization already in progress")
            return
        }
        try {
            VioLogger.debug("[CampaignManager] Loading campaign info from cache")
            loadFromCache()
            VioLogger.debug("[CampaignManager] Fetching v2 mobile bootstrap from API")
            fetchAndApplySdkBootstrap()
        } finally {
            isInitializing.set(false)
        }
    }

    fun shouldShowComponent(type: String): Boolean {
        val id = campaignId
        if (id == null || id <= 0) return true
        if (!_isCampaignActive.value) return false
        
        return _activeComponents.value.any { it.type == type && shouldShowComponent(it) }
    }

    /**
     * Determina si un componente debe mostrarse basándose en su contexto de match.
     * Basado en la lógica de Swift:
     * - Siempre visible si no tiene matchContext (campaign-level)
     * - Visible si tiene matchContext que coincide con el match activo
     */
    fun shouldShowComponent(component: Component): Boolean {
        if (!_isCampaignActive.value) return false
        
        val componentMatchId = component.matchContext?.matchId ?: return true // Campaign-level, always show
        
        // Si tiene matchId, solo mostrar si coincide con el partido activo
        return componentMatchId == currentMatchId
    }

    fun getActiveComponent(type: String, componentId: String? = null): Component? {
        if (!_isCampaignActive.value) return null
        return if (componentId != null) {
            _activeComponents.value.firstOrNull { it.type == type && it.id == componentId && it.isActive }
        } else {
            _activeComponents.value.firstOrNull { it.type == type && it.isActive }
        }
    }

    fun getActiveComponents(type: String): List<Component> {
        if (!_isCampaignActive.value) return emptyList()
        return _activeComponents.value.filter { it.type == type && it.isActive }
    }

    /**
     * Busca un componente activo por su locationId (slot).
     * Si no se especifica locationId, devuelve el primero del tipo solicitado.
     */
    fun getActiveComponent(locationId: String): Component? {
        if (!_isCampaignActive.value) return null
        return _activeComponents.value.firstOrNull { it.locationId == locationId && it.isActive }
    }

    fun setAppBundleId(bundleId: String) {
        appBundleId = bundleId
        VioLogger.debug("App Bundle ID set to: $bundleId", COMPONENT)
    }

    fun setMatchId(matchId: String?) {
        if (matchId == null) {
            setMatchContext(null)
            return
        }
        setMatchContext(MatchContext(matchId = matchId))
    }

    /**
     * Establece el contexto del partido actual, lo que permite filtrar componentes
     * y descubrir campañas específicas para ese match.
     *
     * Limpia componentes del contexto anterior y recarga campañas/componentes para el nuevo contexto.
     */
    fun setMatchContext(matchContext: MatchContext?) {
        if (!isSettingUpBroadcast.compareAndSet(false, true)) {
            VioLogger.debug("Broadcast context setup already in progress", COMPONENT)
            return
        }

        // En Kotlin removimos _activeComponents.value = emptyList() para evitar parpadeos/loading infinito
        // La lista se actualizará reactivamente tras el filtrado o llegada de nuevos eventos.

        if (matchContext == null) {
            currentMatchContext = null
            currentMatchId = null
            VioLogger.debug("Match Context cleared", COMPONENT)
            isSettingUpBroadcast.set(false)
            return
        }

        // Establecer nuevo contexto
        currentMatchContext = matchContext
        currentMatchId = matchContext.matchId

        VioLogger.debug("Current Match Context set to: $matchContext", COMPONENT)
        VioLogger.info("Refreshing campaigns for context=${matchContext.matchId}", COMPONENT)

        // Recargar campañas y componentes para este contexto
        scope.launch {
            try {
                refreshCampaignsForContext(matchContext)
            } finally {
                isSettingUpBroadcast.set(false)
            }
        }
    }

    /**
     * Recarga campañas y componentes para un contexto específico.
     * Verifica si auto-discovery está habilitado y filtra componentes por contexto.
     */
    private suspend fun refreshCampaignsForContext(context: MatchContext) {
        val config = VioConfiguration.shared.state.value
        
        // Verificar si auto-discovery está habilitado
        if (config.campaign.autoDiscover) {
            // Usar auto-discovery
            VioLogger.debug("Auto-discovery enabled, discovering campaigns for match: ${context.matchId}", COMPONENT)
            discoverCampaigns(matchId = context.matchId)
        } else if (campaignId != null && campaignId!! > 0) {
            // Usar modo legacy (campaña única)
            VioLogger.debug("Legacy mode, initializing campaign: $campaignId", COMPONENT)
            initializeCampaign()
        } else {
            VioLogger.warning("No auto-discovery enabled and no configured campaignId; nothing to refresh for match ${context.matchId}", COMPONENT)
        }
        
        // Filtrar componentes por contexto
        filterComponentsByContext(context)
    }

    /**
     * Filtra los componentes activos para mostrar solo los que corresponden al match actual,
     * manteniendo backward compatibility con componentes sin matchContext.
     * 
     * Lógica de filtrado:
     * - Si componente NO tiene matchContext → Mostrar para todos los matches (backward compatibility)
     * - Si componente tiene matchContext.matchId → Solo mostrar si coincide con context.matchId
     * - Si no hay matchContext activo → Solo mostrar componentes sin matchContext
     */
    private fun filterComponentsByContext(context: MatchContext?) {
        val filtered = allComponents.filter { component ->
            val componentMatchId = component.matchContext?.matchId
            
            // Si no hay contexto activo, solo mostramos componentes que NO están ligados a un partido
            if (context == null) {
                return@filter componentMatchId == null
            }
            
            // Incluir componentes sin matchContext (backward compatibility / campaign-level)
            if (componentMatchId == null) {
                return@filter true
            }
            
            // Incluir componentes que coinciden con el matchId actual
            componentMatchId == context.matchId
        }
        
        if (filtered.size != _activeComponents.value.size || allComponents.isEmpty()) {
            VioLogger.info("Filtered components: ${allComponents.size} -> ${filtered.size} for match: ${context?.matchId ?: "none"}", COMPONENT)
            if (filtered.isEmpty() && allComponents.isNotEmpty()) {
                VioLogger.warning("No matching components remain after match filtering. allComponents ids=${allComponents.map { it.id }} locations=${allComponents.map { it.locationId }} matchIds=${allComponents.map { it.matchContext?.matchId }}", COMPONENT)
            }
            _activeComponents.value = filtered
            scope.launch {
                CacheManager.shared.saveComponents(filtered)
            }
        }
    }

    fun disconnect() {
        webSocketManager?.disconnect()
        webSocketManager = null
        _isConnected.value = false
    }

    private fun applyConfiguration(state: VioConfiguration.State) {
        apiKey = state.apiKey
        restApiBaseUrl = state.campaign.restAPIBaseURL
        webSocketBaseUrl = state.campaign.webSocketBaseURL
        campaignApiKey = state.campaign.campaignApiKey
        campaignAdminApiKey = state.campaign.campaignAdminApiKey
        
        val autoDiscover = state.campaign.autoDiscover
        val configuredId = state.liveShow.campaignId
        campaignId = configuredId.takeIf { it > 0 }

        if (autoDiscover) {
            // Modo auto-discovery: no cargar campaña aquí
            // Las campañas se descubrirán cuando se llame setMatchContext()
            VioLogger.info("Auto-discovery enabled, waiting for setMatchContext", COMPONENT)
            
            // Ensure state is ready even without an initial campaign
            _isCampaignActive.value = true
            _campaignState.value = CampaignState.ACTIVE
            _activeComponents.value = emptyList()

            // Si ya había contexto de match previo (setMatchContext pudo llamarse antes de obtener config remota), refrescamos
            currentMatchContext?.let { existingContext ->
                scope.launch {
                    refreshCampaignsForContext(existingContext)
                }
            }
        } else if (configuredId > 0) {
            // Modo legacy: inicializar campaña específica
            scope.launch { initializeCampaign() }
        } else {
            // Sin auto-discovery y sin campaignId: modo pasivo
            _isCampaignActive.value = true
            _campaignState.value = CampaignState.ACTIVE
            _activeComponents.value = emptyList()
        }
    }

    private fun loadFromCache() {
        CacheManager.shared.loadCampaign()?.let { cached ->
            _currentCampaign.value = cached
            _campaignState.value = cached.currentState
            VioLogger.debug("Loaded campaign from cache: ID ${cached.id}", COMPONENT)

            val cachedMethods = cached.paymentMethods
                .filter { it != VioPaymentMethod.UNKNOWN }
                .distinct()
            if (cachedMethods.isNotEmpty()) {
                VioConfiguration.updateCheckoutConfig(CheckoutConfig(paymentMethods = cachedMethods))
            }
        }

        CacheManager.shared.loadCampaignState()?.let { (state, active) ->
            _campaignState.value = state
            _isCampaignActive.value = active
        }

        val cachedComponents = CacheManager.shared.loadComponents()
        if (cachedComponents.isNotEmpty()) {
            allComponents = cachedComponents
            _activeComponents.value = cachedComponents
        }

        // Load multi-campaign data if available
        val cachedActiveCampaigns = CacheManager.shared.loadActiveCampaigns()
        if (cachedActiveCampaigns.isNotEmpty()) {
            _activeCampaigns.value = cachedActiveCampaigns
            VioLogger.debug("Loaded ${cachedActiveCampaigns.size} active campaigns from cache", COMPONENT)
        }

        val cachedDiscoveredCampaigns = CacheManager.shared.loadDiscoveredCampaigns()
        if (cachedDiscoveredCampaigns.isNotEmpty()) {
            _discoveredCampaigns.value = cachedDiscoveredCampaigns
            VioLogger.debug("Loaded ${cachedDiscoveredCampaigns.size} discovered campaigns from cache", COMPONENT)
        }

        // cache age no longer logged
    }

    suspend fun fetchAndApplySdkBootstrap(
        apiKeyOverride: String? = null,
        baseUrlOverride: String? = null,
    ) {
        val effectiveApiKey = (apiKeyOverride ?: campaignApiKey ?: campaignAdminApiKey ?: apiKey).orEmpty()
        if (effectiveApiKey.isBlank()) {
            VioLogger.warning("Skipping v2 bootstrap: missing API key", COMPONENT)
            return
        }

        val baseUrl = baseUrlOverride ?: restApiBaseUrl.ifBlank { "https://api-dev.vio.live" }
        val service = VioSDKConfigService()
        val bootstrap = service.fetchConfig(apiKey = effectiveApiKey, baseUrl = baseUrl) ?: run {
            VioLogger.warning("v2 bootstrap unavailable; preserving current campaign state", COMPONENT)
            return
        }

        val oldLogoUrl = _currentCampaign.value?.campaignLogo
        val campaignBlock = bootstrap.campaign
        val resolvedCampaignId = campaignBlock?.id?.takeIf { it > 0 } ?: campaignId

        val mappedCampaign = resolvedCampaignId?.let { cid ->
            Campaign(
                id = cid,
                isPaused = campaignBlock?.isPaused,
                campaignLogo = campaignBlock?.logo,
            )
        }
        _currentCampaign.value = mappedCampaign
        campaignId = resolvedCampaignId

        val active = (campaignBlock?.isActive != false) && (campaignBlock?.isPaused != true)
        _isCampaignActive.value = active
        _campaignState.value = if (active) CampaignState.ACTIVE else CampaignState.ENDED

        // Components are WS-authoritative in v2.
        allComponents = emptyList()
        _activeComponents.value = emptyList()
        CacheManager.shared.saveComponents(emptyList())

        bootstrap.endpoints?.restBase?.takeIf { it.isNotBlank() }?.let { restApiBaseUrl = it }
        bootstrap.endpoints?.webSocketBase?.takeIf { it.isNotBlank() }?.let { webSocketBaseUrl = it }

        VioConfiguration.applySdkBootstrapSponsors(
            primary = bootstrap.primarySponsor?.toVioSponsor(),
            secondaries = bootstrap.secondarySponsors?.map { it.toVioSponsor() } ?: emptyList(),
        )
        VioConfiguration.applySdkBootstrapCommerce(
            apiKey = bootstrap.primarySponsor?.commerce?.apiKey,
            graphQLURL = bootstrap.endpoints?.commerceGraphQL,
        )

        mappedCampaign?.let {
            CacheManager.shared.saveCampaign(it)
            CacheManager.shared.saveCampaignState(_campaignState.value, _isCampaignActive.value)
        }
        SponsorAssets.update(mappedCampaign?.sponsor)
        emitLogoChanged(oldLogoUrl, mappedCampaign?.campaignLogo)
        uploadPlacementManifestIfPossible()
        fetchAndApplyCampaignComponentsIfPossible()

        if (resolvedCampaignId != null && resolvedCampaignId > 0) {
            connectWebSocket(resolvedCampaignId)
        } else {
            disconnect()
        }
    }

    /**
     * Discovers all available campaigns using the SDK API key (auto-discovery mode).
     * Populates discoveredCampaigns and activeCampaigns, then selects currentCampaign.
     *
     * @param matchId Optional match ID to filter campaigns.
     */
    suspend fun discoverCampaigns(matchId: String? = null) {
        if (_discoveredCampaigns.value.isNotEmpty()) {
            VioLogger.debug("Campaigns already discovered, reusing cached discovery and confirming WebSocket", COMPONENT)
            val selected = selectCurrentCampaign(_activeCampaigns.value)
            if (selected != null) {
                // Asegura que el estado de checkout se mantenga sincronizado incluso cuando usamos cache.
                // Importante para togglear métodos (ej: Google Pay) sin depender de una llamada de red nueva.
                val selectedMethods = selected.paymentMethods
                    .filter { it != VioPaymentMethod.UNKNOWN }
                    .distinct()
                VioConfiguration.updateCheckoutConfig(CheckoutConfig(paymentMethods = selectedMethods))

                VioLogger.debug("Connecting WebSocket for cached active campaign ${selected.id}", COMPONENT)
                connectWebSocket(selected.id)
            } else {
                VioLogger.warning("No active campaign found in cached discovered campaigns", COMPONENT)
            }
            return
        }
        VioLogger.debug("Discovering campaigns via v2 mobile bootstrap...", COMPONENT)
        if (matchId != null) {
            currentMatchId = matchId
        }
        fetchAndApplySdkBootstrap()
        val selected = _currentCampaign.value
        val campaigns = selected?.let { listOf(it) } ?: emptyList()
        _discoveredCampaigns.value = campaigns
        _activeCampaigns.value = campaigns.filter { _isCampaignActive.value && it.isPaused != true }
        scope.launch {
            CacheManager.shared.saveDiscoveredCampaigns(campaigns)
            CacheManager.shared.saveActiveCampaigns(_activeCampaigns.value)
        }
    }

    /**
     * Fuerza un refresh de campañas desde red.
     *
     * Útil para entornos de QA donde el backend puede habilitar/deshabilitar métodos de pago
     * (paymentMethods) y se necesita que el SDK refleje el cambio sin reinstalar / esperar TTL.
     */
    suspend fun forceDiscoverCampaigns(
        matchId: String? = null,
        clearPersistentCache: Boolean = false,
    ) {
        println("forceDiscoverCampaigns ${clearPersistentCache}")
        if (clearPersistentCache) {
            CacheManager.shared.clearCache()
        }
        _discoveredCampaigns.value = emptyList()
        _activeCampaigns.value = emptyList()
        _activeComponents.value = emptyList()
        allComponents = emptyList()
        discoverCampaigns(matchId = matchId)
    }


    /**
     * Selects the current campaign from a list of active campaigns.
     * Strategy: First active, non-paused campaign.
     * Future: Can be extended to support match-based selection or developer preference.
     */
    private fun selectCurrentCampaign(campaigns: List<Campaign>): Campaign? {
        // Strategy 1: First active, non-paused campaign
        return campaigns.firstOrNull { campaign ->
            campaign.currentState == CampaignState.ACTIVE && campaign.isPaused != true
        }
        
        // Future: Match-based selection
        // currentMatchContext?.let { context ->
        //     campaigns.firstOrNull { it.matchContext == context }
        // } ?: campaigns.firstOrNull()
    }

    /**
     * Allows developer to manually select a specific campaign by ID.
     */
    fun selectCampaign(campaignId: Int) {
        val campaign = _discoveredCampaigns.value.firstOrNull { it.id == campaignId }
        if (campaign != null) {
            _currentCampaign.value = campaign
            _campaignState.value = campaign.currentState
            _isCampaignActive.value = campaign.currentState == CampaignState.ACTIVE && campaign.isPaused != true
            SponsorAssets.update(campaign.sponsor)
            VioLogger.success("Manually selected campaign: $campaignId", COMPONENT)
        } else {
            VioLogger.warning("Campaign $campaignId not found in discovered campaigns", COMPONENT)
        }
    }


    suspend fun fetchComponentsFromAllCampaigns() {
        VioLogger.debug("Skipping component polling; WS is authoritative in v2", COMPONENT)
    }

    private suspend fun fetchAndApplyCampaignComponentsIfPossible() {
        val campaign = _currentCampaign.value
        if (campaign == null) {
            VioLogger.debug("fetchAndApplyCampaignComponents: skipped — no current campaign", COMPONENT)
            return
        }

        val effectiveApiKey = campaignAdminApiKey
            ?.takeIf { it.isNotBlank() }
            ?: campaignApiKey?.takeIf { it.isNotBlank() }
            ?: apiKey

        if (effectiveApiKey.isBlank()) {
            VioLogger.warning("fetchAndApplyCampaignComponents: skipped — no API key available", COMPONENT)
            return
        }

        val baseUrl = restApiBaseUrl.ifBlank { "https://api-dev.vio.live" }.trimEnd('/')
        val url = URL("$baseUrl/v2/mobile/campaigns/${campaign.id}/components")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-Key", effectiveApiKey)
        }

        try {
            val status = connection.responseCode
            val body = connection.inputStream.bufferedReader().use { it.readText() }

            if (status !in 200..299) {
                VioLogger.warning("fetchAndApplyCampaignComponents: HTTP status=$status body=$body", COMPONENT)
                return
            }

            val response = JsonUtils.mapper.readValue(body, ComponentsResponseWrapper::class.java)
            val merged = allComponents.toMutableList()

            response.components.forEach { componentResponse ->
                val component = Component.fromResponse(componentResponse)
                val existingIndex = merged.indexOfFirst {
                    it.id == component.id && it.locationId == component.locationId
                }
                if (existingIndex >= 0) {
                    merged[existingIndex] = component
                } else {
                    merged.add(component)
                }
            }

            allComponents = merged
            filterComponentsByContext(currentMatchContext)
            CacheManager.shared.saveComponents(_activeComponents.value)
            VioLogger.info("Fetched /v2/mobile/campaigns/${campaign.id}/components → ${response.components.size} component(s) merged", COMPONENT)
        } catch (error: Throwable) {
            VioLogger.warning("fetchAndApplyCampaignComponents failed (non-fatal): ${error.message}", COMPONENT)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun uploadPlacementManifestIfPossible() {
        val effectiveApiKey = campaignAdminApiKey
            ?.takeIf { it.isNotBlank() }
            ?: campaignApiKey?.takeIf { it.isNotBlank() }
            ?: apiKey

        if (effectiveApiKey.isBlank()) {
            VioLogger.info("Skipping placement manifest upload: no API key available", COMPONENT)
            return
        }

        val baseUrl = restApiBaseUrl.ifBlank { "https://api-dev.vio.live" }
        VioLogger.info("Attempting placement manifest upload to $baseUrl with apiKeySource=${if (campaignAdminApiKey?.isNotBlank() == true) "campaignAdminApiKey" else if (campaignApiKey?.isNotBlank() == true) "campaignApiKey" else "apiKey"}", COMPONENT)
        try {
            VioPlacementManifestUploader.upload(baseUrl, effectiveApiKey)
        } catch (error: Throwable) {
            VioLogger.error("Placement manifest upload failed: ${error.message}", COMPONENT)
        }
    }


    private suspend fun connectWebSocket(campaignId: Int) {
        webSocketManager?.disconnect()
        val manager = CampaignWebSocketManager(
            campaignId = campaignId,
            baseUrl = webSocketBaseUrl,
            apiKey = apiKey,
            contentId = currentMatchId, // Pass current matchId (contentId)
            scope = scope,
        )
        manager.onCampaignStarted = { event -> scope.launch { handleCampaignStarted(event) } }
        manager.onCampaignEnded = { event -> scope.launch { handleCampaignEnded(event) } }
        manager.onCampaignPaused = { event -> scope.launch { handleCampaignPaused(event) } }
        manager.onCampaignResumed = { event -> scope.launch { handleCampaignResumed(event) } }
        manager.onComponentStatusChanged = { event -> scope.launch { handleComponentStatusChanged(event) } }
        manager.onComponentConfigUpdated = { event -> scope.launch { handleComponentConfigUpdated(event) } }
        manager.onPollReceived = { poll -> scope.launch { _events.emit(CampaignNotification.PollReceived(poll)) } }
        manager.onContestReceived = { contest -> scope.launch { _events.emit(CampaignNotification.ContestReceived(contest)) } }
        manager.onConnectionStatusChanged = { connected -> _isConnected.value = connected }
        manager.onCartIntent = { event ->
            publishCartIntentIfChanged(event, channel = "websocket")
        }
        webSocketManager = manager
        manager.connect()
    }

    fun publishCartIntentIfChanged(event: CampaignWebSocketManager.CartIntentEvent, channel: String) {
        val current = _activeCartIntentEvent.value

        // Primary dedup: activationId match
        if (event.activationId != null && current?.activationId == event.activationId) {
            VioLogger.debug("cart_intent [$channel] dedup activationId=${event.activationId}", COMPONENT)
            return
        }

        // Fallback for legacy events with no activationId
        if (event.activationId == null &&
            current != null &&
            current.activationId == null &&
            current.productId == event.productId &&
            current.campaignId == event.campaignId
        ) {
            VioLogger.debug("cart_intent [$channel] dedup legacy (productId,campaignId)", COMPONENT)
            return
        }

        _activeCartIntentEvent.value = event
        scope.launch {
            _events.emit(CampaignNotification.CartIntentReceived(event))
        }
        VioLogger.info(
            "cart_intent applied [$channel] productId=${event.productId} activationId=${event.activationId} sponsorId=${event.sponsorId}",
            COMPONENT,
        )
    }

    /**
     * Handle FCM push notification data.
     * Returns true if this was a Vio cart intent notification that was processed.
     */
    fun handlePushNotification(data: Map<String, Any>): Boolean {
        val event = CampaignWebSocketManager.CartIntentEvent.from(data) ?: return false
        publishCartIntentIfChanged(event, channel = "push")
        return true
    }

    /**
     * Check if the push notification data represents a Vio cart intent.
     */
    private fun isVioCartIntentPayload(data: Map<String, Any>): Boolean {
        return CampaignWebSocketManager.CartIntentEvent.from(data) != null
    }

    private suspend fun handleCampaignStarted(event: CampaignStartedEvent) {
        VioLogger.debug("🔌 [WebSocket] Campaign Started Event received: campaignId=${event.campaignId}, startDate=${event.startDate}, endDate=${event.endDate}", COMPONENT)
        VioLogger.success("Campaign started: ${event.campaignId}", COMPONENT)
        _isCampaignActive.value = true
        _campaignState.value = CampaignState.ACTIVE

        val existingCampaign = _currentCampaign.value
        val oldLogoUrl = existingCampaign?.campaignLogo
        
        val newCampaign = Campaign(
            id = event.campaignId,
            startDate = event.startDate,
            endDate = event.endDate,
            isPaused = false,
        )
        
        _currentCampaign.value = newCampaign
        _currentCampaign.value?.let { CacheManager.shared.saveCampaign(it) }
        CacheManager.shared.saveCampaignState(_campaignState.value, _isCampaignActive.value)

        emitLogoChanged(oldLogoUrl, null) // Events don't include logo, typically clear it or wait for refresh

        _events.emit(CampaignNotification.CampaignStarted(event))
    }

    private suspend fun handleCampaignEnded(event: CampaignEndedEvent) {
        VioLogger.debug("🔌 [WebSocket] Campaign Ended Event received: campaignId=${event.campaignId}, endDate=${event.endDate}", COMPONENT)
        VioLogger.warning("Campaign ended: ${event.campaignId}", COMPONENT)
        _isCampaignActive.value = false
        _campaignState.value = CampaignState.ENDED
        val oldLogoUrl = _currentCampaign.value?.campaignLogo
        allComponents = emptyList()
        _activeComponents.value = emptyList()

        _currentCampaign.value = _currentCampaign.value?.copy(endDate = event.endDate)
            ?: Campaign(id = event.campaignId, startDate = null, endDate = event.endDate, isPaused = null)

        _currentCampaign.value?.let { CacheManager.shared.saveCampaign(it) }
        CacheManager.shared.saveCampaignState(_campaignState.value, _isCampaignActive.value)
        CacheManager.shared.saveComponents(emptyList())

        emitLogoChanged(oldLogoUrl, null)

        _events.emit(CampaignNotification.CampaignEnded(event))
    }

    private suspend fun handleCampaignPaused(event: CampaignPausedEvent) {
        VioLogger.debug("🔌 [WebSocket] Campaign Paused Event received: campaignId=${event.campaignId}", COMPONENT)
        _isCampaignActive.value = false
        allComponents = emptyList()
        _activeComponents.value = emptyList()

        _currentCampaign.value = _currentCampaign.value?.copy(isPaused = true)
            ?: Campaign(id = event.campaignId, startDate = null, endDate = null, isPaused = true)

        _currentCampaign.value?.let { CacheManager.shared.saveCampaign(it) }
        CacheManager.shared.saveCampaignState(_campaignState.value, _isCampaignActive.value)
        CacheManager.shared.saveComponents(emptyList())

        _events.emit(CampaignNotification.CampaignPaused(event))
    }

    private suspend fun handleCampaignResumed(event: CampaignResumedEvent) {
        VioLogger.debug("🔌 [WebSocket] Campaign Resumed Event received: campaignId=${event.campaignId}", COMPONENT)
        VioLogger.success("Campaign resumed: ${event.campaignId}", COMPONENT)
        _isCampaignActive.value = true
        _currentCampaign.value = _currentCampaign.value?.copy(isPaused = false)

        _currentCampaign.value?.let { CacheManager.shared.saveCampaign(it) }
        CacheManager.shared.saveCampaignState(_campaignState.value, _isCampaignActive.value)

        _events.emit(CampaignNotification.CampaignResumed(event))
    }

    private suspend fun handleComponentStatusChanged(event: ComponentStatusChangedEvent) {
        VioLogger.debug("🔌 [WebSocket] Component Status Changed Event received: data=${event.data}, component=${event.component}, status=${event.status}", COMPONENT)
        val (status, componentId) = when {
            event.data != null -> event.data.status to event.data.campaignComponentId.toString()
            event.component != null && event.status != null -> event.status to event.component.id
            else -> null
        } ?: run {
            VioLogger.error("Invalid component_status_changed event - missing required fields", COMPONENT)
            return
        }

        // --- Race Condition Fix matching Swift ---
        // If a message arrives and the currentMatchId in the message doesn't match the SDK state
        val incomingMatchId = event.data?.matchContext?.matchId ?: event.component?.matchContext?.matchId
        if (incomingMatchId != null && incomingMatchId != currentMatchId) {
            VioLogger.warning("Race condition detected: Incoming matchId '$incomingMatchId' does not match current state '$currentMatchId'. Reconnecting WebSocket.", COMPONENT)
            // Trigger brief reconnection to resync state 
            scope.launch {
                webSocketManager?.disconnect()
                campaignId?.let { connectWebSocket(it) }
            }
            return
        }
        // ----------------------------------------

        if (status == "active" && _campaignState.value == CampaignState.UPCOMING) {
            VioLogger.warning("Ignoring component activation - campaign is upcoming", COMPONENT)
            return
        }

        if (status == "active" && _campaignState.value == CampaignState.ENDED) {
            VioLogger.warning("Ignoring component activation - campaign has ended", COMPONENT)
            return
        }

        if (status == "active" && (_currentCampaign.value?.isPaused == true || !_isCampaignActive.value)) {
            VioLogger.warning("Ignoring component activation - campaign is paused", COMPONENT)
            return
        }

        val component = runCatching { event.toComponent() }.onFailure {
            VioLogger.error("Failed to convert component event: ${it.message}", COMPONENT)
        }.getOrNull() ?: return

        if (status == "active") {
            val currentAll = allComponents.toMutableList()
            val index = currentAll.indexOfFirst { it.id == componentId }
            if (index >= 0) {
                currentAll[index] = component
            } else {
                currentAll.add(component)
            }
            allComponents = currentAll
        } else {
            allComponents = allComponents.filterNot { it.id == componentId }
        }

        // Re-filter for UI based on current context
        filterComponentsByContext(currentMatchContext)

        CacheManager.shared.saveComponents(allComponents)
        _events.emit(CampaignNotification.ComponentStatusChanged(event))
    }

    private suspend fun handleComponentConfigUpdated(event: ComponentConfigUpdatedEvent) {
        VioLogger.debug("🔌 [WebSocket] Component Config Updated Event received: component=${event.component}", COMPONENT)
        val component = runCatching { event.toComponent() }.onFailure {
            VioLogger.error("Failed to convert component config event: ${it.message}", COMPONENT)
        }.getOrNull() ?: return

        val componentId = component.id
        var updated = false
        
        val currentAll = allComponents.toMutableList()
        val index = currentAll.indexOfFirst { it.id == componentId }
        if (index >= 0) {
            currentAll[index] = component
            updated = true
        } else if (_isCampaignActive.value && _currentCampaign.value?.isPaused != true) {
            currentAll.add(component)
            updated = true
        } else {
            VioLogger.warning("Cannot add component - campaign not active or paused", COMPONENT)
        }
        
        if (updated) {
            allComponents = currentAll
            filterComponentsByContext(currentMatchContext)
            CacheManager.shared.saveComponents(allComponents)
            VioLogger.success("Updated component config: $componentId", COMPONENT)
            _events.emit(CampaignNotification.ComponentConfigUpdated(event))
        }
    }

    private suspend fun httpGet(url: String, timeout: Int = 30_000): HttpResponse? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            
            // Add Bundle ID header if available
            appBundleId?.let { setRequestProperty("X-App-Bundle-ID", it) }

            // Use campaignAdminApiKey if available, otherwise fallback to general apiKey
            // Note: discoverCampaigns handles its own apiKey selection in its URL construction
            if (!url.contains("apiKey=")) {
                val effectiveApiKey = campaignAdminApiKey?.takeIf { it.isNotBlank() } ?: apiKey
                if (effectiveApiKey.isNotBlank()) {
                    setRequestProperty("X-API-Key", effectiveApiKey)
                }
            }
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResponse(status, body)
        } catch (error: Exception) {
            VioLogger.error("HTTP request failed: ${error.message}", COMPONENT)
            null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun httpPost(url: String, jsonBody: String, timeout: Int = 30_000): HttpResponse? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeout
            readTimeout = timeout
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            
            appBundleId?.let { setRequestProperty("X-App-Bundle-ID", it) }
            
            val effectiveApiKey = campaignAdminApiKey?.takeIf { it.isNotBlank() } ?: apiKey
            if (effectiveApiKey.isNotBlank()) {
                setRequestProperty("X-API-Key", effectiveApiKey)
            }
            
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(jsonBody.toByteArray()) }
            
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResponse(status, body)
        } catch (error: Exception) {
            VioLogger.error("HTTP POST request failed: ${error.message}", COMPONENT)
            null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun preCacheLogo(logoUrl: String) = withContext(Dispatchers.IO) {
        try {
            VioLogger.debug("Pre-caching campaign logo: $logoUrl", COMPONENT)
            val connection = (URL(logoUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val status = connection.responseCode
            if (status in 200..299) {
                // Just read and discard to populate cache
                connection.inputStream.use { it.readBytes() }
                VioLogger.success("Campaign logo pre-cached successfully", COMPONENT)
            } else {
                VioLogger.warning("Failed to pre-cache logo, status: $status", COMPONENT)
            }
            connection.disconnect()
        } catch (error: Exception) {
            VioLogger.error("Logo pre-cache failed: ${error.message}", COMPONENT)
        }
    }

    private data class HttpResponse(val statusCode: Int, val body: String)
}

sealed interface CampaignNotification {
    data class CampaignStarted(val event: CampaignStartedEvent) : CampaignNotification
    data class CampaignEnded(val event: CampaignEndedEvent) : CampaignNotification
    data class CampaignPaused(val event: CampaignPausedEvent) : CampaignNotification
    data class CampaignResumed(val event: CampaignResumedEvent) : CampaignNotification
    data class ComponentStatusChanged(val event: ComponentStatusChangedEvent) : CampaignNotification
    data class ComponentConfigUpdated(val event: ComponentConfigUpdatedEvent) : CampaignNotification
    data class CampaignLogoChanged(val oldLogoUrl: String?, val newLogoUrl: String?) : CampaignNotification
    data class CommerceConfigChanged(val commerce: live.vio.VioCore.models.CommerceConfig) : CampaignNotification
    data class PollReceived(val poll: Poll) : CampaignNotification
    data class ContestReceived(val contest: Contest) : CampaignNotification
    data class CartIntentReceived(val event: CampaignWebSocketManager.CartIntentEvent) : CampaignNotification
}
