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

    fun readDeadline(binding: MobileUsageDeadlineBinding): Long? {
        if (!binding.isValid()) return null
        val key = storageKey(binding)
        if (preferences.getString("${key}_binding", null) != bindingSignature(binding)) {
            return null
        }
        val deadline = preferences.getLong("${key}_deadline", 0L)
        if (deadline <= 0L) {
            clear(binding)
            return null
        }
        return deadline
    }

    fun readDeadline(binding: MobileUsageDeadlineBinding, nowUtcMillis: Long): Long? {
        val deadline = readDeadline(binding) ?: return null
        if (nowUtcMillis > 0L && deadline < nowUtcMillis - MAX_CLOCK_ROLLBACK_TOLERANCE_MS) {
            clear(binding)
            return null
        }
        return deadline
    }

    fun recordDeadline(binding: MobileUsageDeadlineBinding, deadlineUtcMillis: Long) {
        if (!binding.isValid() || deadlineUtcMillis <= 0L) return
        val key = storageKey(binding)
        val existing = readDeadline(binding) ?: 0L
        val effective = maxOf(existing, deadlineUtcMillis)
        preferences.edit()
            .putString("${key}_binding", bindingSignature(binding))
            .putLong("${key}_deadline", effective)
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
