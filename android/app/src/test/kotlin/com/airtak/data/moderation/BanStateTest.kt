// AirTAK Android — test/kotlin/com/airtak/data/moderation/BanStateTest.kt
package com.airtak.data.moderation

import com.airtak.proto.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BanStateTest {
    @Test fun belowThresholdIsNotBanned() {
        val s = BanState(
            targetNodeId = 0xAABBCCDDL,
            scope = Scope.SCOPE_FIRE_TEAM,
            voters = setOf(1L, 2L),
            lastVoteUnix = 100L,
        )
        assertEquals(2, s.voteCount)
        assertFalse(s.isBanned)
    }

    @Test fun threeDistinctVotersTriggersBan() {
        val s = BanState(
            targetNodeId = 1L,
            scope = Scope.SCOPE_SQUAD,
            voters = setOf(10L, 11L, 12L),
            lastVoteUnix = 100L,
        )
        assertTrue(s.isBanned)
    }

    @Test fun duplicateVoterIdsCollapseInSet() {
        // Set semantics already enforce uniqueness; verify guard against a
        // future refactor that switches to List.
        val voters: Set<Long> = listOf(7L, 7L, 7L, 8L).toSet()
        val s = BanState(2L, Scope.SCOPE_ALL, voters, 0L)
        assertEquals(2, s.voteCount)
        assertFalse(s.isBanned)
    }
}
