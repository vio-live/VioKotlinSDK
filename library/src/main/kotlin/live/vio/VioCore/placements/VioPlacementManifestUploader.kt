package live.vio.VioCore.placements

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import live.vio.VioCore.utils.VioLogger
import live.vio.sdk.core.helpers.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

public object VioPlacementManifestUploader {
    private const val COMPONENT = "VioPlacementManifestUploader"

    @JsonIgnoreProperties(ignoreUnknown = true)
    public data class Response(
        val clientAppId: Int,
        val locations: List<PersistedLocation> = emptyList(),
        val deprecatedCount: Int? = null,
        val warnings: List<Warning>? = null,
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public data class PersistedLocation(
            val id: Int,
            val locationId: String,
            val displayName: String? = null,
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        public data class Warning(
            val kind: String? = null,
            val detail: String? = null,
        )
    }

    public suspend fun upload(baseURL: String, apiKey: String): Response? = withContext(Dispatchers.IO) {
        val locations = VioPlacementRegistry.registeredLocations()
        if (locations.isEmpty()) {
            VioLogger.info("Skipping placement manifest upload: no registered locations", COMPONENT)
            return@withContext null
        }

        val trimmedBase = baseURL.trimEnd('/')
        val payload = VioPlacementRegistry.manifestPayload()
        VioLogger.debug("Uploading placement manifest payload=${payload}", COMPONENT)
        val payloadJson = JsonUtils.mapper.writeValueAsString(payload)
        val url = URL("$trimmedBase/v2/mobile/components/manifest")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) {
                setRequestProperty("X-API-Key", apiKey)
            }
            doOutput = true
            outputStream.use { it.write(payloadJson.toByteArray()) }
        }

        try {
            val status = connection.responseCode
            val body = connection.inputStream.bufferedReader().use { it.readText() }

            if (status !in 200..299) {
                VioLogger.error("Placement manifest upload failed: status=$status body=$body", COMPONENT)
                return@withContext null
            }

            val response = JsonUtils.mapper.readValue(body, Response::class.java)
            response.warnings?.forEach { warning ->
                if (!warning.kind.isNullOrBlank() || !warning.detail.isNullOrBlank()) {
                    VioLogger.warning("Manifest warning: ${warning.kind} ${warning.detail}", COMPONENT)
                }
            }
            VioLogger.success("Placement manifest uploaded: ${response.locations.size} locations", COMPONENT)
            return@withContext response
        } catch (error: Throwable) {
            VioLogger.error("Placement manifest upload failed: ${error.message}", COMPONENT)
            return@withContext null
        } finally {
            connection.disconnect()
        }
    }
}
