package com.airtak.ui.help

import com.airtak.R
import com.airtak.ui.settings.Section

/** Links open UI only: never send, delete, restart or flash anything automatically. */
enum class HelpScreen { Pairing, Map, Messages, Players, Markers, Settings, Firmware }
enum class HelpMapAction { Search, DrawMarker, Objective, Status, Tiles, Segments, Route, Profile }

enum class HelpDestination(
    val screen: HelpScreen,
    val mapAction: HelpMapAction? = null,
    val section: Section? = null,
    val messageSlot: Int? = null,
    val directMessages: Boolean = false,
) {
    Pairing(HelpScreen.Pairing),
    Map(HelpScreen.Map),
    Search(HelpScreen.Map, HelpMapAction.Search),
    DrawMarker(HelpScreen.Map, HelpMapAction.DrawMarker),
    Objective(HelpScreen.Map, HelpMapAction.Objective),
    Status(HelpScreen.Map, HelpMapAction.Status),
    Tiles(HelpScreen.Map, HelpMapAction.Tiles),
    Segments(HelpScreen.Map, HelpMapAction.Segments),
    Route(HelpScreen.Map, HelpMapAction.Route),
    Profile(HelpScreen.Map, HelpMapAction.Profile),
    Messages(HelpScreen.Messages),
    Mesh(HelpScreen.Messages, messageSlot = 0),
    Game(HelpScreen.Messages, messageSlot = 1),
    DirectMessages(HelpScreen.Messages, directMessages = true),
    Players(HelpScreen.Players),
    Markers(HelpScreen.Markers),
    Settings(HelpScreen.Settings),
    PlayerSettings(HelpScreen.Settings, section = Section.Player),
    LoraSettings(HelpScreen.Settings, section = Section.Lora),
    ChannelSettings(HelpScreen.Settings, section = Section.Channels),
    PositionSettings(HelpScreen.Settings, section = Section.PositionPower),
    SecuritySettings(HelpScreen.Settings, section = Section.Security),
    ModerationSettings(HelpScreen.Settings, section = Section.Moderation),
    AppSettings(HelpScreen.Settings, section = Section.App),
    LanguageSettings(HelpScreen.Settings, section = Section.Language),
    ShareSettings(HelpScreen.Settings, section = Section.Share),
    Help(HelpScreen.Settings, section = Section.Help),
    About(HelpScreen.Settings, section = Section.About),
    Firmware(HelpScreen.Firmware);

    fun route(nodeId: Long? = null, markerId: Long? = null): String {
        val arguments = listOfNotNull(nodeId?.let { "nodeId=$it" }, markerId?.let { "markerId=$it" })
        return "help/$name" + if (arguments.isEmpty()) "" else "?${arguments.joinToString("&")}"
    }
}

/** Preserve the help return path even when a user switches tabs from a linked screen. */
internal fun helpDestinationForTab(route: String): HelpDestination? = when (route) {
    "map" -> HelpDestination.Map
    "messages" -> HelpDestination.Messages
    "players" -> HelpDestination.Players
    "game" -> HelpDestination.Markers
    "settings" -> HelpDestination.Settings
    "pair" -> HelpDestination.Pairing
    else -> null
}

internal val helpTopicDestinations = listOf(
    HelpDestination.Pairing, HelpDestination.Map, HelpDestination.Pairing,
    HelpDestination.Messages, HelpDestination.Players, HelpDestination.Markers,
    HelpDestination.Settings, HelpDestination.Firmware,
)

internal val helpEntryDestinations = mapOf(
    R.string.help_t1_e1_label to HelpDestination.Pairing,
    R.string.help_t1_e2_label to HelpDestination.Pairing,
    R.string.help_t1_e3_label to HelpDestination.Pairing,
    R.string.help_t1_e4_label to HelpDestination.PlayerSettings,
    R.string.help_t1_e5_label to HelpDestination.Tiles,
    R.string.help_t2_e1_label to HelpDestination.Search,
    R.string.help_t2_e2_label to HelpDestination.DrawMarker,
    R.string.help_t2_e3_label to HelpDestination.Status,
    R.string.help_t2_e4_label to HelpDestination.Status,
    R.string.help_t2_e5_label to HelpDestination.Map,
    R.string.help_t2_e6_label to HelpDestination.Map,
    R.string.help_t2_e7_label to HelpDestination.Map,
    R.string.help_t2_e8_label to HelpDestination.Tiles,
    R.string.help_t2_e9_label to HelpDestination.Segments,
    R.string.help_t2_e10_label to HelpDestination.Route,
    R.string.help_t2_e11_label to HelpDestination.Profile,
    R.string.help_t2_e12_label to HelpDestination.Map,
    R.string.help_t2_e13_label to HelpDestination.Map,
    R.string.help_t2_e14_label to HelpDestination.Messages,
    R.string.help_t2_e15_label to HelpDestination.Players,
    R.string.help_t2_e16_label to HelpDestination.Markers,
    R.string.help_t2_e17_label to HelpDestination.Settings,
    R.string.help_t3_e1_label to HelpDestination.Pairing,
    R.string.help_t3_e2_label to HelpDestination.About,
    R.string.help_t3_e3_label to HelpDestination.PositionSettings,
    R.string.help_t3_e4_label to HelpDestination.About,
    R.string.help_t3_e5_label to HelpDestination.LoraSettings,
    R.string.help_t4_e1_label to HelpDestination.Mesh,
    R.string.help_t4_e2_label to HelpDestination.Game,
    R.string.help_t4_e3_label to HelpDestination.Messages,
    R.string.help_t4_e4_label to HelpDestination.DirectMessages,
    R.string.help_t4_e5_label to HelpDestination.Messages,
    R.string.help_t4_e6_label to HelpDestination.Messages,
    R.string.help_t4_e7_label to HelpDestination.Messages,
    R.string.help_t4_e8_label to HelpDestination.Messages,
    R.string.help_t5_e1_label to HelpDestination.Players,
    R.string.help_t5_e2_label to HelpDestination.Players,
    R.string.help_t5_e3_label to HelpDestination.Players,
    R.string.help_t5_e4_label to HelpDestination.Players,
    R.string.help_t5_e5_label to HelpDestination.Players,
    R.string.help_t5_e6_label to HelpDestination.Players,
    R.string.help_t5_e7_label to HelpDestination.Players,
    R.string.help_t6_e1_label to HelpDestination.Markers,
    R.string.help_t6_e2_label to HelpDestination.Markers,
    R.string.help_t6_e3_label to HelpDestination.Markers,
    R.string.help_t6_e4_label to HelpDestination.DrawMarker,
    R.string.help_t6_e5_label to HelpDestination.Objective,
    R.string.help_t6_e6_label to HelpDestination.Markers,
    R.string.help_t7_e1_label to HelpDestination.PlayerSettings,
    R.string.help_t7_e2_label to HelpDestination.PlayerSettings,
    R.string.help_t7_e3_label to HelpDestination.LoraSettings,
    R.string.help_t7_e4_label to HelpDestination.ChannelSettings,
    R.string.help_t7_e5_label to HelpDestination.ChannelSettings,
    R.string.help_t7_e6_label to HelpDestination.PositionSettings,
    R.string.help_t7_e7_label to HelpDestination.PositionSettings,
    R.string.help_t7_e8_label to HelpDestination.SecuritySettings,
    R.string.help_t7_e9_label to HelpDestination.ModerationSettings,
    R.string.help_t7_e10_label to HelpDestination.AppSettings,
    R.string.help_t7_e11_label to HelpDestination.LanguageSettings,
    R.string.help_t7_e12_label to HelpDestination.Help,
    R.string.help_t7_e13_label to HelpDestination.ShareSettings,
    R.string.help_t7_e14_label to HelpDestination.About,
    R.string.help_t7_e15_label to HelpDestination.About,
    R.string.help_t8_e1_label to HelpDestination.Firmware,
    R.string.help_t8_e2_label to HelpDestination.Firmware,
    R.string.help_t8_e3_label to HelpDestination.Firmware,
    R.string.help_t8_e4_label to HelpDestination.Firmware,
    R.string.help_t8_e5_label to HelpDestination.Firmware,
)
