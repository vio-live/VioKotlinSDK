package live.vio.VioCore

import java.lang.reflect.Method
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.managers.CampaignWebSocketManager
import live.vio.VioCore.models.Component
import live.vio.VioCore.models.MatchContext
import live.vio.VioEngagementSystem.managers.EngagementManager
import live.vio.VioEngagementSystem.models.Poll
import live.vio.VioEngagementSystem.models.PollOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketParityTest {

    @Test
    fun testWebSocketUrlWithContentId() {
        val manager = CampaignWebSocketManager(
            campaignId = 35,
            baseUrl = "https://api-staging.vio.live",
            apiKey = "test_key",
            contentId = "newcastle-united-vs-fc-barcelona-ucl-2026-03-10"
        )
        
        // Use reflection to access private buildSocketUrl method if needed, 
        // or just test the public behavior if possible.
        val buildSocketUrlMethod: Method = CampaignWebSocketManager::class.java.getDeclaredMethod("buildSocketUrl")
        buildSocketUrlMethod.isAccessible = true
        val url = buildSocketUrlMethod.invoke(manager) as String
        
        assertTrue("URL should contain contentId", url.contains("contentId=newcastle-united-vs-fc-barcelona-ucl-2026-03-10"))
        assertTrue("URL should be wss", url.startsWith("wss://"))
        assertTrue("URL should contain campaignId", url.contains("/ws/35"))
    }

    @Test
    fun testComponentFilteringParity() {
        // This is a bit harder to test without mocking the whole singleton, 
        // but we can test the filtering logic in filterComponentsByContext.
        val manager = CampaignManager.shared
        
        // Use reflection to set allComponents and currentMatchId
        val allComponentsField = CampaignManager::class.java.getDeclaredField("allComponents")
        allComponentsField.isAccessible = true
        
        val comp1 = Component(id = "1", type = "carousel", name = "Carousel Component") // No match context
        val comp2 = Component(id = "2", type = "poll", name = "Poll Component", matchContext = MatchContext(matchId = "matchA"))
        val comp3 = Component(id = "3", type = "contest", name = "Contest Component", matchContext = MatchContext(matchId = "matchB"))
        
        allComponentsField.set(manager, listOf(comp1, comp2, comp3))
        
        // Test with matchA
        manager.setMatchId("matchA")
        val filterMethod = CampaignManager::class.java.getDeclaredMethod("filterComponentsByContext", MatchContext::class.java)
        filterMethod.isAccessible = true
        filterMethod.invoke(manager, MatchContext(matchId = "matchA"))
        
        val activeComponents = manager.activeComponents.value
        assertEquals("Should have 2 components for matchA (carousel + pollA)", 2, activeComponents.size)
        assertTrue("Should contain comp1", activeComponents.contains(comp1))
        assertTrue("Should contain comp2", activeComponents.contains(comp2))
        assertFalse("Should NOT contain comp3", activeComponents.contains(comp3))
        
        // Test with matchB
        filterMethod.invoke(manager, MatchContext(matchId = "matchB"))
        val activeComponentsB = manager.activeComponents.value
        assertEquals("Should have 2 components for matchB (carousel + contestB)", 2, activeComponentsB.size)
        assertTrue("Should contain comp1", activeComponentsB.contains(comp1))
        assertTrue("Should contain comp3", activeComponentsB.contains(comp3))
        assertFalse("Should NOT contain comp2", activeComponentsB.contains(comp2))
    }

    @Test
    fun testEngagementManagerReactivePollUpdate() {
        val manager = EngagementManager.shared
        val poll = Poll(
            id = "28",
            broadcastId = "matchA",
            question = "Who wins?",
            options = listOf(
                PollOption(id = "1", text = "Barca", voteCount = 8000, percentage = 54.0),
                PollOption(id = "2", text = "PSG", voteCount = 6000, percentage = 40.0),
                PollOption(id = "3", text = "Ingen", voteCount = 800, percentage = 6.0)
            ),
            totalVotes = 14800
        )
        
        manager.addOrUpdatePoll(poll)
        
        val results = manager.pollResults.value["28"]
        assert(results != null)
        assertEquals(14800, results?.totalVotes)
        assertEquals(54.0, results?.options?.find { it.optionId == "1" }?.percentage ?: 0.0, 0.1)
    }
}
