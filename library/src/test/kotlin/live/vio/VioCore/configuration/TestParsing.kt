package live.vio.VioCore.configuration

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class TestParsing {
    @Test
    fun testParseVioRemoteConfig() {
        val jsonString = """
        {"clientApp":{"id":17,"name":"Viaplay","apiKey":"tv2_api_key_91b4fbf634af4bc5"},"endpoints":{"restBase":"http://api-staging.vio.live","webSocketBase":"http://api-staging.vio.live","commerceGraphQL":"https://graph-ql-dev.vio.live/graphql"},"features":{"engagement":true,"adPlacements":true,"commerce":true,"lineup":true},"commerce":{"apiKey":"KCXF10Y-W5T4PCR-GG5119A-Z64SQ9S","endpoint":"https://graph-ql-dev.vio.live/graphql"},"theme":{"primaryColor":null,"accentColor":null},"markets":[]}
        """.trimIndent()

        val parser = Json { ignoreUnknownKeys = true }
        val remote = parser.decodeFromString(VioRemoteConfig.serializer(), jsonString)
        println("Success:")
        println(remote)
        assertNotNull(remote.commerce)
        assertEquals("KCXF10Y-W5T4PCR-GG5119A-Z64SQ9S", remote.commerce?.apiKey)
    }
}
