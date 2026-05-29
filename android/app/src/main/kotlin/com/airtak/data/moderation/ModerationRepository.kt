// AirTAK Android — data/moderation/ModerationRepository.kt
// Roadmap: 5.10 | Vote-kick / shadowban moderation.
//
// Subscribes to AIRTAK_MODERATION packets and records each vote in the
// moderation_votes table. Exposes a flow of `BannedTarget` aggregating
// distinct voter counts per (target, scope) — a target is considered banned
// when ≥3 distinct VOTE_BAN voters exist within the 24h TTL window and no
// later UNBAN from any of those voters has cleared the entry. UNBAN votes
// from the original voter cohort immediately remove the corresponding rows.
//
// Bans are receiver-side display suppression only (shadowban): the mesh
// continues to relay all packets but PlayersScreen / Map / Messages should
// filter banned targets from their visible lists.

package com.airtak.data.moderation

import com.airtak.data.db.ModerationVoteDao
import com.airtak.data.db.ModerationVoteEntity
import com.airtak.data.mesh.MeshRepository
import com.airtak.config.constants.PORTNUM_AIRTAK_MODERATION
import com.airtak.data.status.DeviceStatusRepository
import com.airtak.config.constants.MODERATION_BAN_TTL_SEC
import com.airtak.config.constants.MODERATION_PRUNE_INTERVAL_MS
import com.airtak.config.constants.MODERATION_REQUIRED_VOTES
import com.airtak.config.constants.MS_PER_SECOND
import com.airtak.config.constants.UINT32_MASK
import com.airtak.proto.ModerationAction
import com.airtak.proto.ModerationPacket
import com.airtak.proto.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Aggregated record of an active ban or in-progress vote. */
data class BanState(
    val targetNodeId: Long,
    val scope: Scope,
    val voters: Set<Long>,
    val lastVoteUnix: Long,
) {
    val voteCount: Int get() = voters.size
    val isBanned: Boolean get() = voteCount >= MODERATION_REQUIRED_VOTES
}


@Singleton
class ModerationRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val dao: ModerationVoteDao,
    private val statusRepo: DeviceStatusRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val votes: Flow<List<ModerationVoteEntity>> = dao.observeAll()

    /** All current ban records (including in-progress: voteCount 1 or 2). */
    val bans: Flow<List<BanState>> = votes.map { aggregate(it) }

    /** Convenience: nodeIds (& their scope) currently shadowbanned. */
    private val _activeBans = MutableStateFlow<Set<Pair<Long, Int>>>(emptySet())
    val activeBans: StateFlow<Set<Pair<Long, Int>>> = _activeBans.asStateFlow()

    init {
        scope.launch {
            mesh.packets
                .filter { it.portnum == PORTNUM_AIRTAK_MODERATION }
                .collect { pkt ->
                    val parsed = runCatching { ModerationPacket.parseFrom(pkt.payload) }.getOrNull() ?: return@collect
                    record(parsed)
                }
        }
        scope.launch {
            bans.collect { list ->
                _activeBans.value = list.filter { it.isBanned }
                    .map { it.targetNodeId to it.scope.number }
                    .toSet()
            }
        }
        // Periodic prune of stale votes (>24h).
        scope.launch {
            while (true) {
                val cutoff = (System.currentTimeMillis() / MS_PER_SECOND) - MODERATION_BAN_TTL_SEC
                runCatching { dao.pruneOlderThan(cutoff) }
                delay(MODERATION_PRUNE_INTERVAL_MS)
            }
        }
    }

    /** Submit a local vote (VOTE_BAN or UNBAN) and broadcast it. */
    suspend fun submitVote(targetNodeId: Long, scopeKind: Scope, action: ModerationAction) {
        val ownNodeId = statusRepo.status.value?.nodeId?.toLong()?.and(UINT32_MASK) ?: return
        val nowUnix = System.currentTimeMillis() / 1000L
        val packet = ModerationPacket.newBuilder()
            .setAction(action)
            .setTargetNodeId(targetNodeId.toInt())
            .setScope(scopeKind)
            .setVoterNodeId(ownNodeId.toInt())
            .setTimestampUnix(nowUnix.toInt())
            .build()
        record(packet)
        mesh.sendModerationPacket(packet)
    }

    private suspend fun record(pkt: ModerationPacket) {
        dao.upsert(
            ModerationVoteEntity(
                targetNodeId = pkt.targetNodeId.toLong() and UINT32_MASK,
                voterNodeId = pkt.voterNodeId.toLong() and UINT32_MASK,
                scopeValue = pkt.scope.number,
                actionValue = pkt.action.number,
                timestampUnix = pkt.timestampUnix.toLong() and UINT32_MASK,
                receivedMs = System.currentTimeMillis(),
            ),
        )
        // UNBAN from any prior voter clears the target+scope ban.
        if (pkt.action == ModerationAction.MODERATION_ACTION_UNBAN) {
            dao.clearForTargetScope(
                pkt.targetNodeId.toLong() and UINT32_MASK,
                pkt.scope.number,
            )
        }
    }

    private fun aggregate(votes: List<ModerationVoteEntity>): List<BanState> {
        val nowUnix = System.currentTimeMillis() / 1000L
        val byTarget = votes
            .filter { nowUnix - it.timestampUnix <= MODERATION_BAN_TTL_SEC }
            .filter { it.actionValue == ModerationAction.MODERATION_ACTION_VOTE_BAN.number }
            .groupBy { it.targetNodeId to it.scopeValue }
        return byTarget.map { (key, rows) ->
            val voters = rows.map { it.voterNodeId }.toSet()
            BanState(
                targetNodeId = key.first,
                scope = Scope.forNumber(key.second) ?: Scope.SCOPE_ALL,
                voters = voters,
                lastVoteUnix = rows.maxOf { it.timestampUnix },
            )
        }
    }
}
