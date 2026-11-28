package com.pim.core.settings

data class ServerUrlValidationResult(
    val input: String,
    val normalizedUrl: String,
    val isValid: Boolean,
    val reasonCode: String? = null,
    val warnings: Set<String> = emptySet()
)

object ServerUrlValidator {
    private val LOCAL_DEV_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]", "10.0.2.2")
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    fun isLocalDevHost(host: String): Boolean = host.lowercase() in LOCAL_DEV_HOSTS

    fun validate(value: String?): ServerUrlValidationResult {
        val input = value?.trim().orEmpty()
        if (input.isBlank()) {
            return ServerUrlValidationResult(
                input = input,
                normalizedUrl = "",
                isValid = false,
                reasonCode = "missing"
            )
        }

        val endpoints = runCatching { PimServerEndpoints.from(input) }.getOrNull()
            ?: return invalid(input, "invalid-api-url")
        val scheme = endpoints.apiBaseUrl.scheme
        val host = endpoints.apiBaseUrl.host.lowercase()

        if (scheme == "http" && !isLocalDevHost(host)) {
            return ServerUrlValidationResult(
                input = input,
                normalizedUrl = endpoints.apiBaseUrl.toString(),
                isValid = false,
                reasonCode = "insecure-http"
            )
        }

        val warnings = buildSet {
            if (host in LOOPBACK_HOSTS) {
                add("real-device-localhost")
            }
            if (scheme == "http") {
                add("cleartext-http")
            }
        }

        return ServerUrlValidationResult(
            input = input,
            normalizedUrl = endpoints.apiBaseUrl.toString(),
            isValid = true,
            warnings = warnings
        )
    }

    private fun invalid(input: String, reasonCode: String): ServerUrlValidationResult {
        return ServerUrlValidationResult(
            input = input,
            normalizedUrl = input,
            isValid = false,
            reasonCode = reasonCode
        )
    }
}
