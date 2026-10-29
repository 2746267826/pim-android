package com.pim.app.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoteAsrStatusLabelTest {
    @Test fun knownInternalStatusesUseChineseUserLabels() {
        val expected = mapOf(
            "Succeeded" to "成功",
            "Queued" to "等待",
            "Running" to "执行中",
            "Prepared" to "待发送",
            "Submitting" to "发送中",
            "VerificationPending" to "待核对",
            "Unknown" to "待核对",
            "Stopped" to "已停止",
            "NeedsAttention" to "需处理",
            "Failed" to "失败",
            "Blocked" to "已阻止",
            "TimedOut" to "超时",
            "FailedValidation" to "校验失败",
            "Cancelled" to "已取消",
            "Incomplete" to "未完成",
        )
        expected.forEach { (wire, label) -> assertEquals(wire, label, noteAsrWireStatusLabel(wire)) }
    }

    @Test fun unrecognizedStatusShowsTheRawValueAndNeverInfersSuccess() {
        val label = noteAsrWireStatusLabel("FutureProviderState")
        assertEquals("未知状态(FutureProviderState)", label)
        assertFalse(label.contains("成功"))
        assertEquals("未知状态(空值)", noteAsrWireStatusLabel("  "))
    }
}
