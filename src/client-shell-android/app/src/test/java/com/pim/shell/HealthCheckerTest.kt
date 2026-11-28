package com.pim.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthCheckerTest {
    @Test fun `healthy returns normalized url`() {
        assertEquals("https://pim.example.com", HealthChecker { 200 }.check("pim.example.com"))
    }
    @Test fun `error status returns null`() {
        assertNull(HealthChecker { 500 }.check("https://pim.example.com"))
    }
    @Test fun `connection failure returns null`() {
        assertNull(HealthChecker { throw java.io.IOException("offline") }.check("https://pim.example.com"))
    }
    @Test fun `invalid address returns null without network call`() {
        var called = false
        assertNull(HealthChecker { called = true; 200 }.check("   "))
        assertEquals(false, called)
    }
    @Test fun `remote cleartext returns null without network call`() {
        var called = false
        assertNull(HealthChecker { called = true; 200 }.check("http://203.0.113.8:5858"))
        assertEquals(false, called)
    }
    @Test fun `local cleartext is allowed for development`() {
        assertEquals("http://127.0.0.1:5858", HealthChecker { 200 }.check("http://127.0.0.1:5858"))
    }
}
