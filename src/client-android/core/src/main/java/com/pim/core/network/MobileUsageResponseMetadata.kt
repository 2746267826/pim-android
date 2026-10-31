package com.pim.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP metadata for one mobile-usage upload attempt.
 *
 * The generated DTO deliberately remains the only contract surface. Until the
 * server contract exposes processing signals, callers may use only transport
 * metadata that already exists on every HTTP response.
 */
data class MobileUsageResponseMetadata(
    val httpStatus: Int,
    val retryAfterHeader: String?
)

class MobileUsageResponseObserver {
    private val latest = AtomicReference<MobileUsageResponseMetadata?>(null)

    fun record(metadata: MobileUsageResponseMetadata) {
        latest.set(
            metadata.copy(
                retryAfterHeader = metadata.retryAfterHeader?.take(MAX_RETRY_AFTER_CHARS)
            )
        )
    }

    fun snapshot(): MobileUsageResponseMetadata? = latest.get()

    fun reset() {
        latest.set(null)
    }

    private companion object {
        const val MAX_RETRY_AFTER_CHARS = 128
    }
}

internal class MobileUsageResponseInterceptor(
    private val observer: MobileUsageResponseObserver
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().url.encodedPath.endsWith(MOBILE_USAGE_PATH_SUFFIX)) {
            observer.record(
                MobileUsageResponseMetadata(
                    httpStatus = response.code,
                    retryAfterHeader = response.header("Retry-After")
                )
            )
        }
        return response
    }

    private companion object {
        const val MOBILE_USAGE_PATH_SUFFIX = "/mobile/usage/events"
    }
}
