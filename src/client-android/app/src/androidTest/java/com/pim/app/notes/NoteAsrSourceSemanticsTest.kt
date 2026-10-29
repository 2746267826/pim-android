package com.pim.app.notes

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.app.ui.theme.PimTheme
import com.pim.core.contract.NoteAsrSourceDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteAsrSourceSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sourceCardExposesOneMergedSemanticsNode() {
        val versionId = "source-semantics-version"
        compose.setContent {
            PimTheme {
                FixedSourceCard(
                    versionId = versionId,
                    source = NoteAsrSourceDto(
                        createdAt = "2026-10-29T00:00:00Z",
                        hasConflict = false,
                        isCurrent = false,
                        noteId = "source-semantics-note",
                        recordings = 1,
                        sampleRate = 16_000,
                        title = "固定版本语义测试",
                        totalSamples = 16_000L * 121,
                        versionId = versionId,
                    ),
                )
            }
        }

        compose.onNodeWithTag("note-asr-source-$versionId").assertExists()
    }

    @Test
    fun unavailableSourceUsesASeparateSemanticsNode() {
        val versionId = "source-unavailable-version"
        compose.setContent {
            PimTheme {
                FixedSourceCard(versionId = versionId, source = null)
            }
        }

        compose.onNodeWithTag("note-asr-source-unavailable-$versionId").assertExists()
        compose.onNodeWithTag("note-asr-source-$versionId").assertDoesNotExist()
    }
}
