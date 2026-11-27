package com.pim.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlValidatorTest {
    @Test
    fun blankUrlIsNotConfigured() {
        val result = ServerUrlValidator.validate("")

        assertFalse(result.isValid)
        assertEquals("missing", result.reasonCode)
        assertEquals("", result.normalizedUrl)
    }

    @Test
    fun remoteCleartextIsRejected() {
        val result = ServerUrlValidator.validate("http://203.0.113.8:5858/api/v1")

        assertFalse(result.isValid)
        assertEquals("insecure-http", result.reasonCode)
        assertEquals("http://203.0.113.8:5858/api/v1/", result.normalizedUrl)
    }

    @Test
    fun remoteHttpsIsAccepted() {
        val result = ServerUrlValidator.validate("https://pim.example.com/api/v1/")

        assertTrue(result.isValid)
        assertEquals("https://pim.example.com/api/v1/", result.normalizedUrl)
    }

    @Test
    fun loopbackCleartextRemainsAvailableForLocalDevelopment() {
        val loopback = ServerUrlValidator.validate("http://127.0.0.1:5858/api/v1/")
        val localhost = ServerUrlValidator.validate("http://localhost:5858/api/v1/")
        val ipv6Loopback = ServerUrlValidator.validate("http://[::1]:5858/api/v1/")

        assertTrue(loopback.isValid)
        assertTrue(localhost.isValid)
        assertTrue(ipv6Loopback.isValid)
        assertTrue(loopback.warnings.contains("cleartext-http"))
    }

    @Test
    fun androidEmulatorHostRemainsAvailableForLocalDevelopment() {
        val result = ServerUrlValidator.validate("http://10.0.2.2:5858/api/v1/")

        assertTrue(result.isValid)
        assertTrue(result.warnings.contains("cleartext-http"))
        assertFalse(result.warnings.contains("real-device-localhost"))
    }

    @Test
    fun rejectsAnythingTheEndpointResolverCannotUse() {
        val invalidUrls = listOf(
            "https://pim.example.com/v1",
            "https://pim.example.com/api/v1/extra",
            "https://pim.example.com/api/v1?tenant=x",
            "https://pim.example.com/api/v1#fragment",
            "https://user:secret@pim.example.com/api/v1"
        )

        invalidUrls.forEach { url ->
            assertFalse(url, ServerUrlValidator.validate(url).isValid)
        }
    }

    @Test
    fun realDeviceLocalhostReceivesWarning() {
        val result = ServerUrlValidator.validate("http://127.0.0.1:5858/api/v1/")

        assertTrue(result.isValid)
        assertTrue(result.warnings.contains("real-device-localhost"))
    }

    @Test
    fun serverSettingsDefaultIsBlankForRealPhones() {
        assertEquals("", ServerSettingsStore.DEFAULT_BASE_URL)
        assertEquals("", ServerSettingsStore.normalizeBaseUrl(""))
    }
}
