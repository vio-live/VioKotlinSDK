package live.vio.sdk

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.configuration.VioEnvironment
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.models.MatchContext
import live.vio.VioCore.utils.VioLogger

/**
 * Entry point público del SDK para apps cliente.
 *
 * Kotlin equivalente aproximado a `VioSDK` en Swift.
 */
object VioSDK {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Indica si el SDK ha sido configurado correctamente con una apiKey.
     */
    val isConfigured: Boolean
        get() = VioConfiguration.shared.isValidConfiguration()

    /**
     * Configura el SDK usando solo la apiKey y un entorno opcional.
     *
     * - Aplica configuración local por defecto inmediatamente.
     * - Lanza una coroutine para obtener `SdkBootstrapResponse` desde el backend (`/v2/mobile/config`).
     * - Cuando llega la respuesta, aplica sponsors + commerce (multi-sponsor).
     */
    fun configure(
        context: Context,
        apiKey: String,
        environment: VioEnvironment = VioEnvironment.PRODUCTION,
        baseUrl: String = "https://api-dev.vio.live",
        userId: String? = null,
    ) {
        println("🚀 [VioSDK] configure called with apiKey=${apiKey.take(8)}...")
        // Aplicar configuración base inmediatamente (sin esperar a la red)
        VioConfiguration.configure(
            context = context,
            apiKey = apiKey,
            environment = environment,
        )

        userId?.let { setUserId(it) }

        // Refrescar configuración remota de forma asíncrona
        scope.launch {
            println("📡 [VioSDK] fetchConfig starting...")
            runCatching {
                CampaignManager.shared.fetchAndApplySdkBootstrap(
                    apiKeyOverride = apiKey,
                    baseUrlOverride = baseUrl,
                )
            }.onFailure {
                VioLogger.error("Error applying v2 mobile bootstrap: ${it.message}", "VioSDK")
            }
            // Marcamos como listo incluso si falló, para que los managers no se queden esperando para siempre
            VioConfiguration.markRemoteConfigReady()
        }
    }

    /**
     * Establece el contenido actual (por ejemplo, un partido o evento)
     * para que el sistema de campañas pueda filtrar componentes por contexto.
     *
     * En Kotlin se modela como un `matchId` de campaña.
     */
    fun setContent(contentId: String) {
        println("🎯 [VioSDK] setContent called with contentId=$contentId")
        VioLogger.debug("Setting content/match context to $contentId", "VioSDK")
        CampaignManager.shared.setMatchContext(MatchContext(matchId = contentId))
    }

    /**
     * Identificador de usuario opcional para analíticas / notificaciones push.
     *
     * En esta versión se deja preparado para futuras integraciones,
     * manteniendo compatibilidad con el API público.
     */
    fun setUserId(userId: String?) {
        VioConfiguration.setUserId(userId)
    }

    /**
     * Limpia el producto abierto actualmente en el overlay (vuelve a estado nulo).
     */
    fun clearOpenedProduct() {
        // En esta implementación simplificada, el estado d producto abierto 
        // se suele manejar en el ViewModel o la UI, pero el SDK proporciona 
        // este bridge para compatibilidad con el API público de Swift.
    }
}

