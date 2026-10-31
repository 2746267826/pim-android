package com.pim.app.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MobileUsageNotBeforeStoreTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var store: MobileUsageNotBeforeStore
    private val binding = MobileUsageDeadlineBinding("server", "owner", "device", "batch")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("not-before-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = MobileUsageNotBeforeStore(preferences)
    }

    @Test
    fun `deadline survives a restart for the same binding only`() {
        val now = 1_000_000L
        assertTrue(store.recordDeadline(binding, now + 15_000L, now))

        // A fresh store instance models the process restart; SharedPreferences is the
        // only state that survives.
        val restarted = MobileUsageNotBeforeStore(preferences)
        assertEquals(now + 15_000L, restarted.readDeadline(binding, now + 1_000L))
        assertEquals(14_000L, restarted.remainingDelayMillis(binding, now + 1_000L))

        val otherOwner = binding.copy(ownerId = "owner-b")
        assertNull(restarted.readDeadline(otherOwner, now + 1_000L))
        assertEquals(0L, restarted.remainingDelayMillis(otherOwner, now + 1_000L))
        assertNull(restarted.readDeadline(binding.copy(deviceId = "device-b"), now + 1_000L))
        assertNull(restarted.readDeadline(binding.copy(batchId = "batch-b"), now + 1_000L))
        assertNull(restarted.readDeadline(binding.copy(serverIdentity = "server-b"), now + 1_000L))
    }

    @Test
    fun `a later shorter deadline never shortens the conservative maximum`() {
        val now = 1_000_000L
        assertTrue(store.recordDeadline(binding, now + 60_000L, now))
        assertTrue(store.recordDeadline(binding, now + 10_000L, now))
        assertEquals(now + 60_000L, store.readDeadline(binding, now))

        assertTrue(store.recordDeadline(binding, now + 90_000L, now))
        assertEquals(now + 90_000L, store.readDeadline(binding, now))
    }

    @Test
    fun `forward clock jump beyond tolerance clears the stale deadline`() {
        val now = 1_000_000L
        assertTrue(store.recordDeadline(binding, now + 60_000L, now))

        val jumpedNow = now + 2L * 60L * 60L * 1_000L
        assertNull(store.readDeadline(binding, jumpedNow))
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun `clear removes the deadline and its recorded-at metadata`() {
        val now = 1_000_000L
        assertTrue(store.recordDeadline(binding, now + 60_000L, now))
        store.clear(binding)

        assertNull(store.readDeadline(binding, now))
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun `stored remaining delay is clamped to one day even for a forged deadline`() {
        val now = 1_000_000L
        assertTrue(store.recordDeadline(binding, now + 10L * 24L * 60L * 60L * 1_000L, now))
        val stored = store.readDeadline(binding, now)
        assertEquals(now + MAX_USAGE_NOT_BEFORE_DELAY_MS, stored)
    }

    @Test
    fun `hard clock rollback rebases the bounded remaining delay instead of waiting years`() {
        val now = 1_000_000_000_000L
        assertTrue(store.recordDeadline(binding, now + 15_000L, now))

        val rolledBackNow = now - 90L * 24L * 60L * 60L * 1_000L
        val rebased = store.readDeadline(binding, rolledBackNow)
        assertEquals(rolledBackNow + 15_000L, rebased)
        assertEquals(15_000L, store.remainingDelayMillis(binding, rolledBackNow))
    }

    @Test
    fun `failed commit is reported instead of silently claiming durability`() {
        val now = 1_000_000L
        val failingStore = MobileUsageNotBeforeStore(FailingCommitPreferences(preferences))
        assertFalse(failingStore.recordDeadline(binding, now + 15_000L, now))
        assertNull(failingStore.readDeadline(binding, now + 1_000L))
    }

    private class FailingCommitPreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = FailingCommitEditor(delegate.edit())
    }

    private class FailingCommitEditor(
        private val delegate: SharedPreferences.Editor
    ) : SharedPreferences.Editor by delegate {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor = this

        override fun clear(): SharedPreferences.Editor = this

        override fun commit(): Boolean = false
    }
}
