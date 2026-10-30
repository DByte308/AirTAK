// AirTAK Android — ui/i18n/Labels.kt
//
// Issue #11: the same four vocabularies (marker type, visibility scope, controlling
// team, priority) were re-declared as private `fun …Label(x): String` in MapScreen,
// MarkersScreen, MessagesScreen and SettingsScreen. Four private copies of English
// words cannot be translated, and translating one of them leaves three behind.
//
// These are the single composable source. They read from `res/values*/strings.xml`,
// so adding a language adds the words everywhere at once.
//
// Deliberately NOT here: `MarkerTool.displayLabel`. That value is used as a fallback
// marker label written into the stored and broadcast marker, so it is protocol data
// and must stay a stable English constant. `uiLabel()` is the display-only sibling.

package com.airtak.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.airtak.R
import com.airtak.proto.MarkerType
import com.airtak.proto.Scope
import com.airtak.proto.TeamControl
import com.airtak.ui.map.MarkerTool

@Composable
fun MarkerType?.labelText(): String = stringResource(
    when (this) {
        MarkerType.MARKER_TYPE_POINT -> R.string.marker_type_point
        MarkerType.MARKER_TYPE_NOTE -> R.string.marker_type_note
        MarkerType.MARKER_TYPE_OBJECTIVE -> R.string.marker_type_objective
        MarkerType.MARKER_TYPE_ROUTE -> R.string.marker_type_route
        MarkerType.MARKER_TYPE_AREA -> R.string.marker_type_area
        else -> R.string.marker_type_unknown
    },
)

@Composable
fun MarkerTool.uiLabel(): String = stringResource(
    when (this) {
        MarkerTool.POINT -> R.string.marker_type_point
        MarkerTool.NOTE -> R.string.marker_type_note
        MarkerTool.OBJECTIVE -> R.string.marker_type_objective
        MarkerTool.ROUTE -> R.string.marker_type_route
        MarkerTool.AREA -> R.string.marker_type_area
    },
)

@Composable
fun Scope?.labelText(): String = stringResource(
    when (this) {
        Scope.SCOPE_COMPANY -> R.string.scope_company
        Scope.SCOPE_PLATOON -> R.string.scope_platoon
        Scope.SCOPE_SQUAD -> R.string.scope_squad
        Scope.SCOPE_FIRE_TEAM -> R.string.scope_fireteam
        else -> R.string.scope_all
    },
)

/**
 * Three-letter badge for a scope chip.
 *
 * This used to be `scopeLabel(scope).take(3).uppercase()`, which silently changed the badge
 * whenever the language changed — German "Alle Spieler" produced "ALE". The badge is a fixed
 * code, not a word, so it is language-independent on purpose.
 */
fun Scope?.badgeCode(): String = when (this) {
    Scope.SCOPE_COMPANY -> "COM"
    Scope.SCOPE_PLATOON -> "PLT"
    Scope.SCOPE_SQUAD -> "SQU"
    Scope.SCOPE_FIRE_TEAM -> "FIR"
    else -> "ALL"
}

@Composable
fun TeamControl.labelText(): String = stringResource(
    when (this) {
        TeamControl.TEAM_CONTROL_OWN -> R.string.team_own
        TeamControl.TEAM_CONTROL_OPPOSING -> R.string.team_opposing
        TeamControl.TEAM_CONTROL_NEUTRAL -> R.string.team_neutral
        else -> R.string.team_unknown
    },
)

@Composable
fun priorityText(priority: Int): String = stringResource(
    when (priority) {
        0 -> R.string.priority_info
        1 -> R.string.priority_low
        2 -> R.string.priority_medium
        3 -> R.string.priority_high
        4 -> R.string.priority_critical
        else -> R.string.priority_medium
    },
)
