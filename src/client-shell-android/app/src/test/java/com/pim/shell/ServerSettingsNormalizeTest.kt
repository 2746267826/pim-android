package com.pim.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsNormalizeTest {
    @Test fun `bare host gets https scheme`() {
        assertEquals("https://pim.example.com", ServerSettingsStore.normalize("pim.example.com"))
    }
    @Test fun `trailing slash is trimmed`() {
        assertEquals("https://pim.example.com", ServerSettingsStore.normalize("https://pim.example.com/"))
    }
    @Test fun `explicit http is preserved`() {
        assertEquals("http://192.168.1.10:5858", ServerSettingsStore.normalize("http://192.168.1.10:5858"))
    }
    @Test fun `blank is rejected`() {
        assertNull(ServerSettingsStore.normalize(""))
        assertNull(ServerSettingsStore.normalize("   "))
        assertNull(ServerSettingsStore.normalize(null))
    }
    @Test fun `non http scheme is rejected`() {
        assertNull(ServerSettingsStore.normalize("ftp://example.com"))
    }
    @Test fun `credentials query and fragment are rejected`() {
        assertNull(ServerSettingsStore.normalize("https://user:pass@example.com"))
        assertNull(ServerSettingsStore.normalize("https://example.com?tenant=x"))
        assertNull(ServerSettingsStore.normalize("https://example.com#fragment"))
    }
    @Test fun `remote cleartext is detected`() {
        assertTrue(ServerSettingsStore.isRemoteCleartext("http://192.168.1.10:5858"))
        assertTrue(ServerSettingsStore.isRemoteCleartext("http://pim.example.com"))
    }
    @Test fun `local development cleartext is not remote`() {
        assertTrue(ServerSettingsStore.isLocalDevHost("127.0.0.1"))
        assertTrue(ServerSettingsStore.isLocalDevHost("localhost"))
        assertTrue(ServerSettingsStore.isLocalDevHost("::1"))
        assertTrue(ServerSettingsStore.isLocalDevHost("10.0.2.2"))
        assertTrue(!ServerSettingsStore.isRemoteCleartext("http://127.0.0.1:5858"))
        assertTrue(!ServerSettingsStore.isRemoteCleartext("http://10.0.2.2:5858"))
        assertTrue(!ServerSettingsStore.isRemoteCleartext("https://pim.example.com"))
    }
}
