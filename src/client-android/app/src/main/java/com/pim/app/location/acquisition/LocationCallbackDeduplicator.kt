package com.pim.app.location.acquisition

import com.pim.app.location.LocationSnapshot
import com.pim.app.location.policy.GeoDistance
import com.pim.app.location.policy.PolicyLocation
import kotlin.math.abs

internal enum class LocationDuplicateReason(val code: String) {
    EXACT_REPLAY("exact-replay"),
    GNSS_FUSED_SAME_MONOTONIC_FIX("gnss-fused-same-monotonic-fix")
}

internal data class LocationDuplicateSuppression(
    val reason: LocationDuplicateReason,
    val suppressed: LocationSnapshot,
    val retained: LocationSnapshot
)

internal data class LocationCallbackDedupResult(
    val delivered: List<LocationSnapshot>,
    val suppressions: List<LocationDuplicateSuppression>,
    val cumulativeSuppressedCount: Long
)

/**
 * Collapses duplicate representations of one platform update.
 *
 * A FusedLocationProvider result can carry both the raw GNSS fix and the fused
 * estimate. Those are two views of one physical sample. This filter is
 * deliberately identity-based: exact callbacks collapse, and a GPS/fused pair
 * collapses only when Android reports the exact same positive monotonic fix
 * timestamp. Nearby fixes or missing/zero-identity timestamps stay independent,
 * preserving the no-rate-limit contract. Recent identities are retained in a
 * small bounded map so a delayed counterpart cannot reappear as a second sample.
 */
internal class LocationCallbackDeduplicator {
    private var lastDelivered: LocationSnapshot? = null
    private val deliveredByIdentity = LinkedHashMap<String, LocationSnapshot>()
    private var suppressedDuplicateCount = 0L

    @Synchronized
    fun filterForDelivery(candidates: List<LocationSnapshot>): LocationCallbackDedupResult {
        val accepted = mutableListOf<LocationSnapshot>()
        val suppressions = mutableListOf<LocationDuplicateSuppression>()
        candidates
            .sortedWith(compareBy<LocationSnapshot> { it.timeMillis })
            .forEach { candidate ->
                val duplicate = findDuplicate(candidate)
                if (duplicate != null) {
                    suppressedDuplicateCount += 1L
                    val keepCandidate = isPreferred(candidate, duplicate.retained)
                    val exactIdentity = exactIdentity(candidate)
                    if (exactIdentity != null && keepCandidate) {
                        val acceptedIndex = accepted.indexOfFirst { it === duplicate.retained }
                        if (acceptedIndex >= 0) {
                            accepted[acceptedIndex] = candidate
                        }
                        deliveredByIdentity[exactIdentity] = candidate
                        lastDelivered = candidate
                    }
                    suppressions += LocationDuplicateSuppression(
                        reason = duplicate.reason,
                        suppressed = if (keepCandidate) duplicate.retained else candidate,
                        retained = if (keepCandidate) candidate else duplicate.retained
                    )
                    return@forEach
                }
                accepted += candidate
                remember(candidate)
            }
        return LocationCallbackDedupResult(
            delivered = accepted,
            suppressions = suppressions,
            cumulativeSuppressedCount = suppressedDuplicateCount
        )
    }

    private fun isPreferred(candidate: LocationSnapshot, current: LocationSnapshot): Boolean {
        val candidateAccuracy = candidate.horizontalAccuracyMeters ?: Float.POSITIVE_INFINITY
        val currentAccuracy = current.horizontalAccuracyMeters ?: Float.POSITIVE_INFINITY
        if (candidateAccuracy != currentAccuracy) return candidateAccuracy < currentAccuracy
        val candidatePriority = providerPriority(candidate.provider)
        val currentPriority = providerPriority(current.provider)
        if (candidatePriority != currentPriority) return candidatePriority < currentPriority
        return candidate.timeMillis > current.timeMillis
    }

    private fun duplicateReason(
        previous: LocationSnapshot,
        candidate: LocationSnapshot
    ): LocationDuplicateReason? {
        val timeDiff = abs(previous.timeMillis - candidate.timeMillis)
        val distance = GeoDistance.metersBetween(
            PolicyLocation(previous.latitude, previous.longitude, previous.timeMillis),
            PolicyLocation(candidate.latitude, candidate.longitude, candidate.timeMillis)
        )
        val previousProvider = previous.provider.lowercase()
        val candidateProvider = candidate.provider.lowercase()
        val sameMonotonicFix = sameMonotonicFix(previous, candidate)
        if (previousProvider == candidateProvider &&
            timeDiff == 0L &&
            distance <= EXACT_FIX_DISTANCE_METERS &&
            sameMonotonicFix
        ) {
            return LocationDuplicateReason.EXACT_REPLAY
        }

        val isGnssFusedPair =
            (isGnss(previousProvider) && isFused(candidateProvider)) ||
                (isFused(previousProvider) && isGnss(candidateProvider))
        if (!isGnssFusedPair) return null

        return if (sameMonotonicFix && distance <= GNSS_FUSED_MAX_POSITION_DELTA_METERS) {
            LocationDuplicateReason.GNSS_FUSED_SAME_MONOTONIC_FIX
        } else {
            null
        }
    }

    private fun findDuplicate(candidate: LocationSnapshot): Duplicate? {
        val exactIdentity = exactIdentity(candidate)
        if (exactIdentity != null) {
            deliveredByIdentity[exactIdentity]?.let { previous ->
                duplicateReason(previous, candidate)?.let { reason ->
                    return Duplicate(previous, reason)
                }
            }
        }
        lastDelivered?.let { previous ->
            duplicateReason(previous, candidate)?.let { reason ->
                return Duplicate(previous, reason)
            }
        }
        deliveredByIdentity.values.forEach { previous ->
            duplicateReason(previous, candidate)?.let { reason ->
                return Duplicate(previous, reason)
            }
        }
        return null
    }

    private fun remember(candidate: LocationSnapshot) {
        val identity = exactIdentity(candidate)
        if (identity != null) {
            deliveredByIdentity[identity] = candidate
        }
        lastDelivered = candidate
        if (deliveredByIdentity.size > MAX_IDENTITIES) {
            deliveredByIdentity.remove(deliveredByIdentity.keys.first())
        }
    }

    private fun exactIdentity(snapshot: LocationSnapshot): String? {
        val elapsed = snapshot.elapsedRealtimeNanos ?: return null
        if (elapsed <= 0L) return null
        return listOf(
            snapshot.provider.lowercase(),
            snapshot.elapsedRealtimeNanos,
            snapshot.timeMillis,
            snapshot.latitude.toBits(),
            snapshot.longitude.toBits()
        ).joinToString(separator = "|")
    }

    private fun sameMonotonicFix(
        previous: LocationSnapshot,
        candidate: LocationSnapshot
    ): Boolean {
        val previousElapsed = previous.elapsedRealtimeNanos
        val candidateElapsed = candidate.elapsedRealtimeNanos
        if (previousElapsed == null || candidateElapsed == null) return false
        return previousElapsed > 0L && previousElapsed == candidateElapsed
    }

    private fun providerPriority(provider: String): Int = when (provider.lowercase()) {
        "gps", "gnss" -> 0
        "fused" -> 1
        else -> 2
    }

    private fun isGnss(provider: String): Boolean = provider == "gps" || provider == "gnss"

    private fun isFused(provider: String): Boolean = provider == "fused"

    private data class Duplicate(
        val retained: LocationSnapshot,
        val reason: LocationDuplicateReason
    )

    private companion object {
        const val MAX_IDENTITIES = 256
        const val GNSS_FUSED_MAX_POSITION_DELTA_METERS = 15.0
        const val EXACT_FIX_DISTANCE_METERS = 2.0
    }
}
