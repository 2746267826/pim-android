package com.pim.app.nativework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTaskStatusFilterTest {
    @Test
    fun emptyOrAllBlankMeansNoFilter() {
        assertEquals(emptyList<String>(), NativeTaskStatusFilter.normalize(emptyList()))
        assertEquals(emptyList<String>(), NativeTaskStatusFilter.normalize(listOf("", " ")))
    }

    @Test
    fun duplicatesAreDroppedAndSelectionOrderIsKept() {
        assertEquals(
            listOf("IN-PROCESS", "COMPLETED"),
            NativeTaskStatusFilter.normalize(
                listOf("IN-PROCESS", "COMPLETED", "IN-PROCESS")
            ),
        )
    }

    @Test
    fun commaSeparatedUnknownOrBlankMixedValuesAreRejected() {
        for (invalid in listOf(
            listOf("NEEDS-ACTION,IN-PROCESS"),
            listOf("UNKNOWN"),
            listOf("needs-action"),
            listOf(" NEEDS-ACTION "),
            listOf("NEEDS-ACTION", ""),
            listOf("NEEDS-ACTION", " "),
        )) {
            assertThrows(
                "must reject $invalid",
                NativeTaskStatusFilterException::class.java,
            ) { NativeTaskStatusFilter.normalize(invalid) }
        }
    }

    @Test
    fun labelsStaySimplifiedChineseAndKnownStatusesUsePlainText() {
        assertEquals("待处理", NativeTaskStatusFilter.label("NEEDS-ACTION"))
        assertEquals("进行中", NativeTaskStatusFilter.label("IN-PROCESS"))
        assertEquals("已完成", NativeTaskStatusFilter.label("COMPLETED"))
        assertEquals("已取消", NativeTaskStatusFilter.label("CANCELLED"))
        assertEquals("UNKNOWN", NativeTaskStatusFilter.label("UNKNOWN"))
        assertTrue(NativeTaskStatusFilter.all.size == 4)
    }
}
