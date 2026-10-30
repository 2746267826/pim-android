package com.pim.app.ui.status

import com.pim.app.mobile.diagnostics.DiagnosticExportResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticExportPartialFeedbackTest {
    @Test
    fun partialExportIsExposedAsPartialFeedbackWithSummary() = runTest {
        val file = File("pim-diagnostics-1.zip")
        val summary = "日志导出不完整：省略 10 字节、1 个文件、0 条超长行；详见 manifest.json"
        val states = mutableListOf<ExportFeedbackState>()

        runExportWithFeedback(
            includeRecentLocations = false,
            export = {
                DiagnosticExportResult(
                    file = file,
                    coordinateCount = 0,
                    isPartial = true,
                    summary = summary
                )
            },
            onState = { states += it }
        )

        assertEquals(
            ExportFeedbackState(
                isExporting = false,
                feedback = DiagnosticExportFeedback.PackageReadyPartial,
                exportedFile = file,
                coordinateCount = 0,
                partialSummary = summary
            ),
            states.last()
        )
    }
}
