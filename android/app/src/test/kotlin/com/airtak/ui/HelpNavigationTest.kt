package com.airtak.ui

import com.airtak.R
import com.airtak.ui.help.HelpDestination
import com.airtak.ui.help.HelpMapAction
import com.airtak.ui.help.HelpScreen
import com.airtak.ui.help.helpDestinationForTab
import com.airtak.ui.help.helpEntryDestinations
import com.airtak.ui.help.helpEntryLabelResources
import com.airtak.ui.help.helpTopicDestinations
import com.airtak.ui.settings.Section
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HelpNavigationTest {
    @Test fun everyHelpEntryHasExactlyOneDestination() {
        assertEquals(68, helpEntryLabelResources.size)
        assertEquals(helpEntryLabelResources.toSet(), helpEntryDestinations.keys)
        assertEquals(helpEntryLabelResources.size, helpEntryDestinations.size)
    }

    @Test fun everyTopicHasADestination() {
        assertEquals(8, helpTopicDestinations.size)
    }

    @Test fun objectiveOpensObjectiveDrawingNotAGenericTab() {
        val target = helpEntryDestinations.getValue(R.string.help_t6_e5_label)
        assertEquals(HelpScreen.Map, target.screen)
        assertEquals(HelpMapAction.Objective, target.mapAction)
    }

    @Test fun createAndEditOpensDrawingTools() {
        assertEquals(HelpDestination.DrawMarker, helpEntryDestinations[R.string.help_t6_e4_label])
    }

    @Test fun settingsLinksOpenTheirSpecificSection() {
        assertEquals(Section.Lora, helpEntryDestinations.getValue(R.string.help_t7_e3_label).section)
        assertEquals(Section.Channels, helpEntryDestinations.getValue(R.string.help_t7_e4_label).section)
        assertEquals(Section.Language, helpEntryDestinations.getValue(R.string.help_t7_e11_label).section)
    }

    @Test fun messageLinksSelectTheirChannelOrDirectMessages() {
        assertEquals(0, helpEntryDestinations.getValue(R.string.help_t4_e1_label).messageSlot)
        assertEquals(1, helpEntryDestinations.getValue(R.string.help_t4_e2_label).messageSlot)
        assertTrue(helpEntryDestinations.getValue(R.string.help_t4_e4_label).directMessages)
    }

    @Test fun destructiveAndPlayerSpecificTopicsOnlyOpenASelectionScreen() {
        assertEquals(HelpDestination.Markers, helpEntryDestinations[R.string.help_t6_e6_label])
        assertEquals(HelpDestination.Players, helpEntryDestinations[R.string.help_t5_e5_label])
        assertEquals(HelpDestination.Firmware, helpEntryDestinations[R.string.help_t8_e1_label])
    }

    @Test fun helpLinksUsePushedRoutesRatherThanTopLevelRoutes() {
        val routes = HelpDestination.entries.map { it.route() }
        assertEquals(routes.size, routes.toSet().size)
        routes.forEach { assertTrue(it.startsWith("help/"), it) }
        assertFalse(routes.any { it in listOf("map", "settings", "messages", "players", "game") })
    }

    @Test fun nestedPlayerAndMarkerNavigationRetainsTheHelpRoute() {
        assertEquals("help/DirectMessages?nodeId=123", HelpDestination.DirectMessages.route(nodeId = 123L))
        assertEquals("help/Map?markerId=456", HelpDestination.Map.route(markerId = 456L))
    }

    @Test fun tabSwitchesInsideHelpRetainHelpDestinations() {
        listOf("map", "messages", "players", "game", "settings", "pair").forEach {
            assertTrue(helpDestinationForTab(it)?.route()?.startsWith("help/") == true)
        }
        assertNull(helpDestinationForTab("unknown"))
    }
}
