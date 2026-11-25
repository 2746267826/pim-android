package com.pim.app.keepalive

import com.pim.app.keepalive.ui.keepAliveReadFailureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeepAliveErrorTextTest {
    @Test
    fun `keepalive read failure never exposes exception text`() {
        val text = keepAliveReadFailureText()

        assertEquals("保活状态暂时无法读取，请稍后重试", text)
        assertFalse(text.contains("Exception"))
        assertFalse(text.contains("token", ignoreCase = true))
    }
}
