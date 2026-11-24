package com.pim.app.nativework

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarViewPreferenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun selectedViewRoundTripsOnDevice() {
        CalendarViewPreference.write(context, CalendarViewMode.Month)

        assertEquals(CalendarViewMode.Month, CalendarViewPreference.read(context))
    }

    @Test
    fun malformedStoredViewIsIgnored() {
        context.getSharedPreferences("pim_calendar_view", Context.MODE_PRIVATE)
            .edit()
            .putString("view", "Bogus")
            .commit()

        assertNull(CalendarViewPreference.read(context))
    }
}
