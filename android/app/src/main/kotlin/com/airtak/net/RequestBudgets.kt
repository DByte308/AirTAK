package com.airtak.net

import java.util.concurrent.ConcurrentHashMap

// Owns every outbound request budget in one place, so sharing is the default rather than something
// each caller has to remember.
//
// The tile budget is per provider and shared by live map traffic and bulk downloads. Two separate
// budgets for the same provider add up: a live pacer and a bulk pacer at 250 ms each is really
// 8 req/s at one provider, which is precisely the failure pacing exists to prevent. Callers ask
// for the budget belonging to a host instead of constructing their own pacer, so the wrong thing
// is not reachable by forgetting to share.
//
// The geocoder is a different service with a different policy and a different published limit, so
// it deliberately does not share the tile budgets.

class RequestBudgets(
    private val tileSpacingMs: Long = TILE_MIN_SPACING_MS,
    private val geocoderSpacingMs: Long = GEOCODER_MIN_SPACING_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tileBudgets = ConcurrentHashMap<String, MinimumIntervalPacer>()

    /**
     * The one budget for [host], shared by every caller targeting it. Keyed on the normalised host
     * so `A.Tiles.Example` and `a.tiles.example` cannot obtain two budgets for one provider.
     */
    fun tileBudgetFor(host: String): MinimumIntervalPacer =
        tileBudgets.computeIfAbsent(host.lowercase().trimEnd('.')) {
            MinimumIntervalPacer(tileSpacingMs, clock)
        }

    /** Nominatim asks for at most one request per second. Never shared with tile traffic. */
    val geocoderBudget: MinimumIntervalPacer by lazy {
        MinimumIntervalPacer(geocoderSpacingMs, clock)
    }

    /** Distinct providers currently under budget. Two hosts must never collapse to one budget. */
    fun trackedTileHosts(): Set<String> = tileBudgets.keys.toSet()
}
