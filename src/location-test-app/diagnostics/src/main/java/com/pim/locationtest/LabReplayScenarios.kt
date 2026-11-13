package com.pim.locationtest

/** Explicitly synthetic coordinates; never presented as replay of a user's redacted track. */
object LabReplayScenarios {
    data class Result(val name: String, val passed: Boolean, val detail: String)

    fun run(): List<Result> {
        val scenarios = listOf("静止速度毛刺", "单次位置跳变", "精度交替", "正常匀速（惯性可安静）", "高速后停车", "定位中断")
        return scenarios.mapIndexed { scenario, name ->
            val tracker = CorroboratedHighSpeedTracker()
            var everActive = false
            val count = if (scenario == 4) 40 else 24
            repeat(count) { index ->
                val east = when (scenario) {
                    0, 2 -> 0.0
                    1 -> if (index < 3) 0.0 else 150.0
                    4 -> minOf(index, 15) * 10.0
                    else -> index * 10.0
                }
                val sample = LocationSample(
                    elapsedRealtimeMillis = index * 2_500L,
                    latitude = 31.0, longitude = 120.0 + east / 95_000.0,
                    horizontalAccuracyMeters = if (scenario == 2 && index % 3 != 0) 140f else 5f,
                    speedMetersPerSecond = if (scenario < 3 || scenario == 4 && index > 15) 20f else 4f,
                    speedAccuracyMetersPerSecond = 1.5f
                )
                tracker.observe(sample)
                tracker.advance(sample.elapsedRealtimeMillis + 1_000)
                everActive = everActive || tracker.mode == ProposedMode.Active
            }
            if (scenario == 5) {
                for (delta in 1_000L..25_000L step 1_000L) tracker.advance(23 * 2_500L + delta)
            }
            val passed = when (scenario) {
                in 0..2 -> !everActive
                3 -> everActive && tracker.mode == ProposedMode.Active
                else -> everActive && tracker.mode == ProposedMode.Inactive
            }
            Result(name, passed, "ever_active=$everActive; final=${tracker.mode}; ${tracker.reason}")
        }
    }
}
