package com.pim.app.mobile.sync

import com.pim.core.network.MobileUsageResponseMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileUsageRetryPolicyTest {

    @Test
    fun `non-202 responses never impose a not-before deadline`() {
        assertNull(resolveMobileUsageNotBefore(MobileUsageResponseMetadata(200, "15"), 1_000L))
        assertNull(resolveMobileUsageNotBefore(MobileUsageResponseMetadata(500, "15"), 1_000L))
        assertNull(resolveMobileUsageNotBefore(null, 1_000L))
    }

    @Test
    fun `missing invalid and non-positive retry-after fall back to the safe default`() {
        val base = 1_000_000L
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, null), base))
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, "not-a-number"), base))
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, ""), base))
        assertEquals(base + 30_000L, resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, "   "), base))
        assertEquals(base + 1_000L, resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, "0"), base))
        assertEquals(base + 1_000L, resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, "-5"), base))
    }

    @Test
    fun `huge retry-after values are capped at one day without overflow`() {
        val base = 1_000_000L
        assertEquals(
            base + MAX_USAGE_NOT_BEFORE_DELAY_MS,
            resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, Long.MAX_VALUE.toString()), base)
        )
        assertEquals(
            base + MAX_USAGE_NOT_BEFORE_DELAY_MS,
            resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, "99999999999999999999"), base)
        )
    }

    @Test
    fun `http-date retry-after uses the parsed instant when valid and falls back when not`() {
        val base = 1_000_000L
        assertEquals(
            base + 1_000L,
            resolveMobileUsageNotBefore(
                MobileUsageResponseMetadata(202, "Thu, 01 Jan 1970 00:00:00 GMT"),
                base
            )
        )
        assertEquals(
            base + 30_000L,
            resolveMobileUsageNotBefore(MobileUsageResponseMetadata(202, "Fri, 99 Foo 9999 99:99 GMT"), base)
        )
    }

    @Test
    fun `remaining delay conversion always rounds up to whole seconds`() {
        assertEquals(0L, retryDelaySecondsForRemainingMillis(0L))
        assertEquals(0L, retryDelaySecondsForRemainingMillis(-1L))
        assertEquals(1L, retryDelaySecondsForRemainingMillis(1L))
        assertEquals(1L, retryDelaySecondsForRemainingMillis(1_000L))
        assertEquals(2L, retryDelaySecondsForRemainingMillis(1_001L))
        assertEquals(
            Long.MAX_VALUE / 1_000L,
            retryDelaySecondsForRemainingMillis(Long.MAX_VALUE)
        )
    }
}
