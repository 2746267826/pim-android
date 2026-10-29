package com.pim.app.status

import com.pim.app.mobile.sync.UsageOutboxRecoveryPreview
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class LegacyOutboxClaimPromptController(
    private val preview: suspend () -> UsageOutboxRecoveryPreview?,
    private val submitNormal: () -> Unit,
    private val submitClaim: (UsageOutboxRecoveryPreview) -> Unit
) {
    private val requesting = AtomicBoolean(false)
    private val _prompt = MutableStateFlow<UsageOutboxRecoveryPreview?>(null)
    val prompt: StateFlow<UsageOutboxRecoveryPreview?> = _prompt.asStateFlow()

    suspend fun requestSync() {
        if (!requesting.compareAndSet(false, true)) return
        try {
            val candidate = try {
                preview()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (candidate == null) {
                submitNormal()
            } else {
                _prompt.value = candidate
            }
        } finally {
            requesting.set(false)
        }
    }

    fun confirm() {
        val candidate = _prompt.value ?: return
        _prompt.value = null
        submitClaim(candidate)
    }

    fun dismiss() {
        _prompt.value = null
    }
}
