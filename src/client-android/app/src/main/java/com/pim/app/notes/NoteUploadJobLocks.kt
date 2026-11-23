package com.pim.app.notes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A job's server request and the receipt it persists must be one serialized
 * step. Otherwise an older GET can land after a newer part PUT and replace the
 * confirmed parts with a stale list.
 */
internal object NoteUploadJobLocks {
    private val guard = Any()
    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(id: String): Mutex = synchronized(guard) {
        locks.getOrPut(id) { Mutex() }
    }

    suspend fun <T> withLock(id: String, block: suspend () -> T): T =
        lockFor(id).withLock { block() }
}
