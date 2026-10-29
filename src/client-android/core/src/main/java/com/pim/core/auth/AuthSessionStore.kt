package com.pim.core.auth

enum class AuthMode { Required, Anonymous }

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtUtcMillis: Long
)

data class AuthSessionSnapshot(
    val tokens: AuthTokens?,
    val serverIdentity: String? = null,
    val ownerId: String? = null,
    val ownerName: String? = null
)

interface AuthSessionStore {
    fun snapshot(): AuthSessionSnapshot
    fun save(
        accessToken: String,
        refreshToken: String,
        expiresAtUtcMillis: Long,
        serverIdentity: String
    ): Boolean
    fun clear(): Boolean

    /** Identity comes from the authenticated server reply, never from decoding an unverified JWT. */
    fun saveOwned(accessToken: String, refreshToken: String, expiresAtUtcMillis: Long,
        serverIdentity: String, ownerId: String, ownerName: String?): Boolean = false

    fun saveRefreshed(expected: AuthSessionSnapshot, tokens: AuthTokens, ownerId: String?, ownerName: String?): Boolean {
        if (snapshot() != expected) return false
        return if (ownerId != null) saveOwned(tokens.accessToken, tokens.refreshToken, tokens.expiresAtUtcMillis,
            expected.serverIdentity ?: return false, ownerId, ownerName)
        else save(tokens.accessToken, tokens.refreshToken, tokens.expiresAtUtcMillis, expected.serverIdentity ?: return false)
    }

    fun clearIfCurrent(expected: AuthSessionSnapshot): Boolean = snapshot() == expected && clear()

    fun accessToken(): String? = snapshot().tokens?.accessToken
    fun refreshToken(): String? = snapshot().tokens?.refreshToken
    fun expiresAtUtcMillis(): Long? = snapshot().tokens?.expiresAtUtcMillis
    fun accessTokenForServerIdentity(serverIdentity: String): String? {
        val current = snapshot()
        return current.tokens?.accessToken?.takeIf { current.serverIdentity == serverIdentity }
    }
}

sealed interface AuthRefreshResult {
    data class Success(val tokens: AuthTokens, val ownerId: String? = null, val ownerName: String? = null) : AuthRefreshResult
    data object Rejected : AuthRefreshResult
}

fun interface AuthRefreshOperation {
    suspend fun refresh(refreshToken: String, serverIdentity: String): AuthRefreshResult
}
