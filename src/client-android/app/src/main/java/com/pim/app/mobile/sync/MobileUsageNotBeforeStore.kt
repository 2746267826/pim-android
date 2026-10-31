package com.pim.app.mobile.sync

import android.content.Context
import android.content.SharedPreferences

internal data class MobileUsageDeadlineBinding(
    val serverIdentity: String,
    val ownerId: String,
    val deviceId: String,
    val batchId: String
) {
    fun isValid(): Boolean =
        serverIdentity.isNotBlank() &&
            ownerId.isNotBlank() &&
            deviceId.isNotBlank() &&
            batchId.isNotBlank()
}

internal class MobileUsageNotBeforeStore(
    private val preferences: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("pim_mobile_sync_state", Context.MODE_PRIVATE)
    )

    fun readDeadline(binding: MobileUsageDeadlineBinding, nowUtcMillis: Long): Long? {
        if (!binding.isValid()) return null
        val key = storageKey(binding)
        if (preferences.getString("${key}_binding", null) != bindingSignature(binding)) {
            return null
        }
        val deadline = preferences.getLong("${key}_deadline", 0L)
        val recordedAt = preferences.getLong("${key}_recorded_at", 0L)
        if (deadline <= 0L) {
            clear(binding)
            return null
        }
        if (nowUtcMillis <= 0L || recordedAt <= 0L) return deadline
        val remainingMillis = (deadline - recordedAt).coerceIn(1L, MAX_USAGE_NOT_BEFORE_DELAY_MS)
        if (nowUtcMillis < safeAdd(recordedAt, -MAX_CLOCK_ROLLBACK_TOLERANCE_MS)) {
            // Hard clock rollback: rebase on the bounded remaining delay instead of
            // letting the absolute deadline wait for months or years.
            val rebasedDeadline = safeAdd(nowUtcMillis, remainingMillis)
            val writeSucceeded = preferences.edit()
                .putString("${key}_binding", bindingSignature(binding))
                .putLong("${key}_deadline", rebasedDeadline)
                .putLong("${key}_recorded_at", nowUtcMillis)
                .commit()
            if (!writeSucceeded) return null
            return rebasedDeadline
        }
        if (deadline < safeAdd(nowUtcMillis, -MAX_CLOCK_ROLLBACK_TOLERANCE_MS)) {
            clear(binding)
            return null
        }
        return deadline
    }

    fun recordDeadline(
        binding: MobileUsageDeadlineBinding,
        deadlineUtcMillis: Long,
        nowUtcMillis: Long
    ): Boolean {
        if (!binding.isValid() || deadlineUtcMillis <= 0L || nowUtcMillis <= 0L) return false
        val key = storageKey(binding)
        val existingDeadline = readDeadline(binding, nowUtcMillis)
        val existingRemaining = existingDeadline
            ?.let { (it - nowUtcMillis).coerceIn(0L, MAX_USAGE_NOT_BEFORE_DELAY_MS) }
            ?: 0L
        val remainingMillis = (deadlineUtcMillis - nowUtcMillis)
            .coerceIn(1L, MAX_USAGE_NOT_BEFORE_DELAY_MS)
            .coerceAtLeast(existingRemaining)
        return preferences.edit()
            .putString("${key}_binding", bindingSignature(binding))
            .putLong("${key}_deadline", safeAdd(nowUtcMillis, remainingMillis))
            .putLong("${key}_recorded_at", nowUtcMillis)
            .commit()
    }

    fun remainingDelayMillis(
        binding: MobileUsageDeadlineBinding,
        nowUtcMillis: Long
    ): Long {
        val deadline = readDeadline(binding, nowUtcMillis) ?: return 0L
        return safeAdd(deadline, -nowUtcMillis).coerceAtLeast(0L)
    }

    fun clear(binding: MobileUsageDeadlineBinding) {
        if (!binding.isValid()) return
        val key = storageKey(binding)
        preferences.edit()
            .remove("${key}_binding")
            .remove("${key}_deadline")
            .commit()
    }

    private fun storageKey(binding: MobileUsageDeadlineBinding): String =
        "usage_not_before:${sha256Hex(bindingSignature(binding))}"

    private fun bindingSignature(binding: MobileUsageDeadlineBinding): String =
        listOf(
            binding.serverIdentity,
            binding.ownerId,
            binding.deviceId,
            binding.batchId
        ).joinToString("\u0000")

    private companion object {
        const val MAX_CLOCK_ROLLBACK_TOLERANCE_MS = 60L * 60L * 1_000L
    }
}
