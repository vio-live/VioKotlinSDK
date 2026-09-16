package live.vio.VioCore.configuration

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import live.vio.VioCore.models.SdkBootstrapResponse
import live.vio.VioCore.utils.VioLogger

/**
 * Servicio ligero para obtener la configuración del SDK desde el backend.
 *
 * Equivalente aproximado a `VioSDKConfigService` en Swift.
 */
class VioSDKConfigService(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    companion object {
        private const val COMPONENT = "VioSDKConfigService"
        private const val DEFAULT_BASE_URL = "https://api-staging.vio.live"
        private const val CONFIG_PATH = "/v2/mobile/config"
        private const val CACHE_TTL_MILLIS: Long = 5 * 60 * 1000 // 5 minutos

        // Cache simple en memoria compartida por todo el proceso.
        @Volatile
        private var cachedConfig: SdkBootstrapResponse? = null

        @Volatile
        private var cachedAt: Long = 0

        fun clearCache() {
            cachedConfig = null
            cachedAt = 0
        }

        private fun isCacheValid(now: Long = System.currentTimeMillis()): Boolean {
            val ts = cachedAt
            return ts != 0L && (now - ts) <= CACHE_TTL_MILLIS
        }
    }

    /**
     * Obtiene la configuración remota del SDK.
     *
     * - Usa cache en memoria con TTL de 5 minutos.
     * - Si cualquier error ocurre devuelve `null` para permitir fallback a defaults.
     */
    suspend fun fetchConfig(
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
    ): SdkBootstrapResponse? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            VioLogger.warning("API key vacía al pedir configuración remota", COMPONENT)
            return@withContext null
        }

        if (isCacheValid()) {
            cachedConfig?.let {
                VioLogger.debug("Usando configuración remota cacheada", COMPONENT)
                return@withContext it
            }
        }

        val normalizedBase = baseUrl.trimEnd('/')
        val url = "$normalizedBase$CONFIG_PATH?apiKey=$apiKey"

        runCatching {
            VioLogger.debug("Solicitando configuración remota desde $url", COMPONENT)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-API-Key", apiKey)
            }

            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (status !in 200..299) {
                    VioLogger.error("Fallo al obtener configuración remota (status=$status, body=$body)", COMPONENT)
                    return@runCatching null
                }

                if (body.trimStart().startsWith("<")) {
                    VioLogger.error("Respuesta HTML inesperada desde endpoint de configuración. Body: $body", COMPONENT)
                    return@runCatching null
                }

                val remote = json.decodeFromString(SdkBootstrapResponse.serializer(), body)
                cachedConfig = remote
                cachedAt = System.currentTimeMillis()
                VioLogger.success("Configuración remota del SDK cargada correctamente", COMPONENT)
                remote
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            VioLogger.error("Error al obtener configuración remota: ${error.message}", COMPONENT)
            null
        }
    }
}

