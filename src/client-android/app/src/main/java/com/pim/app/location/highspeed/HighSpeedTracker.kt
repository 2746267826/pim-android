package com.pim.app.location.highspeed

/** 高速轨迹模式状态：未激活 / 触发累计中（密集采样确认）/ 高速档生效。 */
enum class HighSpeedMode {
    Inactive,
    Accumulating,
    Active
}

/**
 * 高速轨迹模式状态机（设计文档 PR#63 §2.1）：纯逻辑，不依赖 Android API，可单测。
 *
 * - 未激活：GPS speed ≥ 8 km/h 持续 10s → [HighSpeedMode.Active]；期间为
 *   [HighSpeedMode.Accumulating]（策略引擎借此切换到 2.5s 密集采样以便快速确认）。
 *   短暂的低速毛刺可被 3s 容差吸收；持续低速仍会重置触发累计。
 * - 激活：speed < 7 km/h 或 speed 为 null 持续 60s → 回落 [HighSpeedMode.Inactive]。
 *   新鲜且连续的 Walking/Still 证据持续 60s，且其中大部分时段的 GPS 速度
 *   低于 8 km/h 时，也可回落；偶发高速度毛刺允许通过，但单一低速点或持续
 *   高速不能被 AR 的 Walking 误标覆盖。10s 的可靠高速会清空更早的步行证据，
 *   避免“先步行、后上高速”仍被旧低速样本带出密集档。
 * - 速度来源为 GPS 多普勒速度 [android.location.Location.getSpeed]，不依赖运动检测。
 *
 * 时钟使用单调时钟（elapsedRealtime），避免墙钟回拨/调整破坏防抖累计。
 */
class HighSpeedTracker(
    private val triggerSpeedMetersPerSecond: Float = TRIGGER_SPEED_METERS_PER_SECOND,
    private val fallbackSpeedMetersPerSecond: Float = FALLBACK_SPEED_METERS_PER_SECOND,
    private val triggerDebounceMillis: Long = TRIGGER_DEBOUNCE_MILLIS,
    private val fallbackDebounceMillis: Long = FALLBACK_DEBOUNCE_MILLIS,
    private val nowElapsedRealtimeMillis: () -> Long
) {
    var mode: HighSpeedMode = HighSpeedMode.Inactive
        private set

    /** 进入高速档的单调时钟时刻；未激活时为 null。 */
    var activeSinceElapsedRealtimeMillis: Long? = null
        private set

    private var fastStreakStartAtMillis: Long? = null
    private var lowSpeedSinceMillis: Long? = null
    private var slowStreakStartAtMillis: Long? = null
    private var reliableHighSpeedSinceMillis: Long? = null
    private var walkingEvidenceSinceMillis: Long? = null
    private var lastWalkingEvidenceAtMillis: Long? = null
    private var walkingCorroborationSinceMillis: Long? = null
    private var lastWalkingLowSpeedAtMillis: Long? = null
    private var walkingObservationCount: Long = 0L
    private var walkingLowSpeedObservationCount: Long = 0L

    fun observe(
        speedMetersPerSecond: Float?,
        freshWalkingOrStill: Boolean = false
    ) {
        val now = nowElapsedRealtimeMillis()
        when (mode) {
            HighSpeedMode.Active -> observeWhileActive(
                speedMetersPerSecond = speedMetersPerSecond,
                freshWalkingOrStill = freshWalkingOrStill,
                now = now
            )
            else -> observeWhileIdle(speedMetersPerSecond, now)
        }
    }

    fun reset() {
        mode = HighSpeedMode.Inactive
        activeSinceElapsedRealtimeMillis = null
        fastStreakStartAtMillis = null
        lowSpeedSinceMillis = null
        slowStreakStartAtMillis = null
        reliableHighSpeedSinceMillis = null
        walkingEvidenceSinceMillis = null
        lastWalkingEvidenceAtMillis = null
        walkingCorroborationSinceMillis = null
        lastWalkingLowSpeedAtMillis = null
        walkingObservationCount = 0L
        walkingLowSpeedObservationCount = 0L
    }

    private fun observeWhileIdle(speedMetersPerSecond: Float?, now: Long) {
        if (speedMetersPerSecond != null && speedMetersPerSecond >= triggerSpeedMetersPerSecond) {
            val lowSince = lowSpeedSinceMillis
            val lowSpeedPersisted = lowSince != null &&
                now - lowSince >= LOW_SPEED_TOLERANCE_MILLIS
            val start = if (lowSpeedPersisted) {
                now
            } else {
                fastStreakStartAtMillis ?: now
            }
            fastStreakStartAtMillis = start
            lowSpeedSinceMillis = null
            if (now - start >= triggerDebounceMillis) {
                enterActive(now)
            } else {
                mode = HighSpeedMode.Accumulating
            }
        } else {
            if (fastStreakStartAtMillis == null) {
                lowSpeedSinceMillis = null
                return
            }
            val lowSince = lowSpeedSinceMillis ?: now
            lowSpeedSinceMillis = lowSince
            if (now - lowSince >= LOW_SPEED_TOLERANCE_MILLIS) {
                fastStreakStartAtMillis = null
                lowSpeedSinceMillis = null
                if (mode == HighSpeedMode.Accumulating) {
                    mode = HighSpeedMode.Inactive
                }
            }
        }
    }

    private fun observeWhileActive(
        speedMetersPerSecond: Float?,
        freshWalkingOrStill: Boolean,
        now: Long
    ) {
        val lowSpeedEvidence = speedMetersPerSecond == null ||
            speedMetersPerSecond < fallbackSpeedMetersPerSecond
        if (lowSpeedEvidence) {
            val start = slowStreakStartAtMillis ?: now
            slowStreakStartAtMillis = start
            if (now - start >= fallbackDebounceMillis) {
                exitActive()
                return
            }
        } else {
            slowStreakStartAtMillis = null
        }

        if (speedMetersPerSecond != null &&
            speedMetersPerSecond >= triggerSpeedMetersPerSecond
        ) {
            val highSpeedStart = reliableHighSpeedSinceMillis ?: now
            reliableHighSpeedSinceMillis = highSpeedStart
            if (now - highSpeedStart >= RELIABLE_HIGH_SPEED_RESET_MILLIS) {
                resetWalkingEvidence()
            }
        } else {
            reliableHighSpeedSinceMillis = null
        }

        if (freshWalkingOrStill) {
            val lastMotion = lastWalkingEvidenceAtMillis
            if (lastMotion != null && now - lastMotion > MOTION_EVIDENCE_GAP_TOLERANCE_MILLIS) {
                resetWalkingEvidence()
            }
            val walkingStart = walkingEvidenceSinceMillis ?: now
            walkingEvidenceSinceMillis = walkingStart
            lastWalkingEvidenceAtMillis = now

            val walkingSpeedCorroborates = speedMetersPerSecond != null &&
                speedMetersPerSecond < triggerSpeedMetersPerSecond
            walkingObservationCount += 1L
            if (walkingSpeedCorroborates) {
                walkingLowSpeedObservationCount += 1L
                val priorLowSpeedAt = lastWalkingLowSpeedAtMillis
                val corroborationStart = walkingCorroborationSinceMillis
                if (priorLowSpeedAt != null &&
                    now - priorLowSpeedAt > MOTION_EVIDENCE_GAP_TOLERANCE_MILLIS
                ) {
                    walkingCorroborationSinceMillis = now
                } else if (corroborationStart == null) {
                    walkingCorroborationSinceMillis = now
                }
                lastWalkingLowSpeedAtMillis = now
            } else {
                val lastLowSpeedAt = lastWalkingLowSpeedAtMillis
                if (lastLowSpeedAt == null ||
                    now - lastLowSpeedAt > MOTION_EVIDENCE_GAP_TOLERANCE_MILLIS
                ) {
                    walkingCorroborationSinceMillis = null
                    lastWalkingLowSpeedAtMillis = null
                }
            }

            val corroborationStart = walkingCorroborationSinceMillis
            val mostlyLowSpeedEvidence =
                walkingLowSpeedObservationCount * 3L >= walkingObservationCount * 2L
            if (now - walkingStart >= fallbackDebounceMillis &&
                corroborationStart != null &&
                now - corroborationStart >= WALKING_MIN_SUB_TRIGGER_EVIDENCE_MILLIS &&
                mostlyLowSpeedEvidence
            ) {
                exitActive()
            }
        } else {
            resetWalkingEvidence()
        }
    }

    private fun resetWalkingEvidence() {
        walkingEvidenceSinceMillis = null
        lastWalkingEvidenceAtMillis = null
        walkingCorroborationSinceMillis = null
        lastWalkingLowSpeedAtMillis = null
        walkingObservationCount = 0L
        walkingLowSpeedObservationCount = 0L
    }

    private fun enterActive(now: Long) {
        mode = HighSpeedMode.Active
        activeSinceElapsedRealtimeMillis = now
        fastStreakStartAtMillis = null
        lowSpeedSinceMillis = null
        slowStreakStartAtMillis = null
        reliableHighSpeedSinceMillis = null
        walkingEvidenceSinceMillis = null
        lastWalkingEvidenceAtMillis = null
        walkingCorroborationSinceMillis = null
        lastWalkingLowSpeedAtMillis = null
        walkingObservationCount = 0L
        walkingLowSpeedObservationCount = 0L
    }

    private fun exitActive() {
        mode = HighSpeedMode.Inactive
        activeSinceElapsedRealtimeMillis = null
        fastStreakStartAtMillis = null
        lowSpeedSinceMillis = null
        slowStreakStartAtMillis = null
        reliableHighSpeedSinceMillis = null
        walkingEvidenceSinceMillis = null
        lastWalkingEvidenceAtMillis = null
        walkingCorroborationSinceMillis = null
        lastWalkingLowSpeedAtMillis = null
        walkingObservationCount = 0L
        walkingLowSpeedObservationCount = 0L
    }

    companion object {
        const val TRIGGER_SPEED_KMH = 8f
        const val FALLBACK_SPEED_KMH = 7f
        const val TRIGGER_DEBOUNCE_MILLIS = 10_000L
        const val FALLBACK_DEBOUNCE_MILLIS = 60_000L
        const val LOW_SPEED_TOLERANCE_MILLIS = 3_000L
        const val MOTION_EVIDENCE_GAP_TOLERANCE_MILLIS = 10_000L
        const val RELIABLE_HIGH_SPEED_RESET_MILLIS = 10_000L
        const val WALKING_MIN_SUB_TRIGGER_EVIDENCE_MILLIS = 30_000L
        const val TRIGGER_SPEED_METERS_PER_SECOND = TRIGGER_SPEED_KMH / 3.6f
        const val FALLBACK_SPEED_METERS_PER_SECOND = FALLBACK_SPEED_KMH / 3.6f
    }
}
