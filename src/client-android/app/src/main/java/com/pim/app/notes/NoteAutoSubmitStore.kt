package com.pim.app.notes

import android.content.Context
import com.pim.core.network.BoundUploadSession

data class NoteAutoSubmitAuthorization(val serverIdentity: String, val ownerId: String)

internal data class NoteAutoSubmitIntent(
    val versionId: String,
    val serverIdentity: String,
    val ownerId: String,
    val ownerName: String,
) {
    val binding get() = BoundUploadSession(serverIdentity, ownerId)
}

internal class NoteAutoSubmitStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun authorization(): NoteAutoSubmitAuthorization? {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val owner = prefs.getString(KEY_OWNER, null) ?: return null
        return runCatching { BoundUploadSession(server, owner); NoteAutoSubmitAuthorization(server, owner) }.getOrNull()
    }

    @Synchronized
    fun authorize(target: NoteUploadTarget): Boolean {
        val requested = NoteAutoSubmitAuthorization(target.binding.serverIdentity, target.binding.ownerId)
        val editor = prefs.edit()
        if (authorization() != requested) editor.clear()
        return editor.putBoolean(KEY_ENABLED, true).putString(KEY_SERVER, requested.serverIdentity)
            .putString(KEY_OWNER, requested.ownerId).commit()
    }

    @Synchronized
    fun authorizeForVersion(target: NoteUploadTarget, versionId: String): Boolean {
        val requested = NoteAutoSubmitAuthorization(target.binding.serverIdentity, target.binding.ownerId)
        val same = authorization() == requested
        val ids = if (same) pendingIds() else emptySet()
        val editor = prefs.edit()
        if (!same) editor.clear()
        editor.putBoolean(KEY_ENABLED, true).putString(KEY_SERVER, requested.serverIdentity).putString(KEY_OWNER, requested.ownerId)
        writeIntent(editor, versionId, target, ids)
        return editor.commit()
    }

    @Synchronized
    fun addIntentIfAuthorized(target: NoteUploadTarget, versionId: String): Boolean {
        if (authorization() != NoteAutoSubmitAuthorization(target.binding.serverIdentity, target.binding.ownerId)) return false
        return prefs.edit().apply { writeIntent(this, versionId, target) }.commit()
    }

    @Synchronized
    fun intent(versionId: String): NoteAutoSubmitIntent? {
        if (!validNoteId(versionId) || versionId !in pendingIds()) return null
        val prefix = "$INTENT_PREFIX$versionId."
        val server = prefs.getString(prefix + SERVER_SUFFIX, null) ?: return null
        val owner = prefs.getString(prefix + OWNER_SUFFIX, null) ?: return null
        val name = prefs.getString(prefix + NAME_SUFFIX, null) ?: ""
        return runCatching { BoundUploadSession(server, owner); NoteAutoSubmitIntent(versionId, server, owner, name) }.getOrNull()
    }

    @Synchronized fun pendingVersionIds(): Set<String> = pendingIds()

    @Synchronized
    fun removeIntent(versionId: String): Boolean {
        if (!validNoteId(versionId)) return false
        val prefix = "$INTENT_PREFIX$versionId."
        return prefs.edit().remove(prefix + SERVER_SUFFIX).remove(prefix + OWNER_SUFFIX).remove(prefix + NAME_SUFFIX)
            .putStringSet(KEY_PENDING, pendingIds() - versionId).commit()
    }

    @Synchronized
    fun revoke(): Pair<Boolean, Set<String>> {
        val ids = pendingIds()
        return prefs.edit().clear().commit() to ids
    }

    private fun pendingIds() = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toSet()

    private fun writeIntent(editor: android.content.SharedPreferences.Editor, versionId: String, target: NoteUploadTarget,
        existingIds: Set<String> = pendingIds()) {
        require(validNoteId(versionId))
        val prefix = "$INTENT_PREFIX$versionId."
        editor.putString(prefix + SERVER_SUFFIX, target.binding.serverIdentity).putString(prefix + OWNER_SUFFIX, target.binding.ownerId)
            .putString(prefix + NAME_SUFFIX, target.name.take(200)).putStringSet(KEY_PENDING, existingIds + versionId)
    }

    companion object {
        private const val PREFERENCES = "native_note_upload_preferences"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SERVER = "server"
        private const val KEY_OWNER = "owner"
        private const val KEY_PENDING = "pending_version_ids"
        private const val INTENT_PREFIX = "auto_intent."
        private const val SERVER_SUFFIX = "server"
        private const val OWNER_SUFFIX = "owner"
        private const val NAME_SUFFIX = "name"
    }
}
