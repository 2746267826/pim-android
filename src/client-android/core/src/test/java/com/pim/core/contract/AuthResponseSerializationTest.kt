package com.pim.core.contract

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 契约生成模型的反序列化行为。
 *
 * 模型来自 `contract/openapi.json`（`AuthResponseApiResponse` / `AuthResponse`），
 * 字段名与 required 都按契约来 —— 这里同时锁住「契约说什么就是什么」：
 * `data.user` 在契约里是 **required**，因此服务端少发它不是「宽容一下」，而是**显式失败**。
 */
class AuthResponseSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun authResponseDecodesContractUserField() {
        val response = json.decodeFromString<AuthResponseApiResponse>(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "accessToken": "access-token",
                "refreshToken": "refresh-token",
                "expiresAt": "2026-07-07T09:00:00Z",
                "user": {
                  "id": "3dc80613-e62b-4af2-970b-1022e781e67e",
                  "username": "alice",
                  "displayName": "Alice",
                  "role": "user"
                }
              },
              "timestamp": "2026-07-07T09:00:00Z"
            }
            """.trimIndent()
        )

        val auth = response.data

        assertNotNull(auth)
        assertEquals("access-token", auth!!.accessToken)
        assertEquals("refresh-token", auth.refreshToken)
        assertEquals("alice", auth.user.username)
        assertEquals("Alice", auth.user.displayName)
    }

    @Test
    fun authResponseRejectsLegacyNonContractUserInfoField() {
        // 旧手写模型用 @JsonNames("userInfo") 兼容过非契约字段名；生成模型只认契约里的 `user`，
        // 报文只带旧名时不是「宽容放行」，而是**显式失败** —— 服务端字段名与契约不符要立刻可见。
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString<AuthResponseApiResponse>(LEGACY_USER_INFO_PAYLOAD)
        }
    }

    @Test
    fun authResponseRejectsPayloadWithoutContractRequiredUser() {
        // 契约 `AuthResponse.required` 含 `user`；完全没有 `user` 的报文同样必须失败，
        // 不允许静默放行半张会话（token 有、用户身份没有）。
        assertThrows(MissingFieldException::class.java) {
            json.decodeFromString<AuthResponseApiResponse>(MISSING_USER_PAYLOAD)
        }
    }

    private companion object {
        private val LEGACY_USER_INFO_PAYLOAD = """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "accessToken": "access-token",
                "refreshToken": "refresh-token",
                "expiresAt": "2026-07-07T09:00:00Z",
                "userInfo": {
                  "id": "3dc80613-e62b-4af2-970b-1022e781e67e",
                  "username": "alice",
                  "displayName": "Alice",
                  "role": "user"
                }
              },
              "timestamp": "2026-07-07T09:00:00Z"
            }
        """.trimIndent()

        private val MISSING_USER_PAYLOAD = """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "accessToken": "access-token",
                "refreshToken": "refresh-token",
                "expiresAt": "2026-07-07T09:00:00Z"
              },
              "timestamp": "2026-07-07T09:00:00Z"
            }
        """.trimIndent()
    }
}
