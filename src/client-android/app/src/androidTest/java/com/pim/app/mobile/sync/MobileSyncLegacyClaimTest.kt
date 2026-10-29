package com.pim.app.mobile.sync

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pim.app.status.LegacyOutboxClaimPromptController
import com.pim.app.ui.components.LegacyOutboxClaimDialog
import com.pim.app.ui.theme.PimTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device coverage for the real legacy usage-outbox confirmation surface.
 *
 * The fixture is synthetic and never contacts an API. It exercises the production dialog,
 * prompt controller, queue preview values, cancellation, and owner-binding decision helper.
 */
@RunWith(AndroidJUnit4::class)
class MobileSyncLegacyClaimTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun legacyDialogShowsOwnerServerCountAndTimeRange() {
        val fixture = SyntheticLegacyOutboxFixture()
        val preview = fixture.preview()

        composeTestRule.setContent {
            PimTheme {
                LegacyOutboxClaimDialog(
                    preview = preview,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("legacy-outbox-claim-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("当前账号：Owner A").assertIsDisplayed()
        composeTestRule.onNodeWithText("当前服务器：${OWNER_A.serverIdentity}").assertIsDisplayed()
        composeTestRule.onNodeWithText("待传记录：42 条").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "时间范围：${formatRange(FIXTURE_START_UTC, FIXTURE_END_UTC)}"
        ).assertIsDisplayed()
    }

    @Test
    fun cancelLeavesLegacyQueueUnboundAndSubmitsNothing() = runBlocking {
        val fixture = SyntheticLegacyOutboxFixture()
        val before = fixture.snapshot()
        var normalSubmissions = 0
        var claimSubmissions = 0
        val controller = LegacyOutboxClaimPromptController(
            preview = { fixture.preview() },
            submitNormal = { normalSubmissions++ },
            submitClaim = {
                claimSubmissions++
                fixture.applyClaim(OWNER_A, it.toRequest())
            }
        )
        controller.requestSync()
        showDialog(controller)

        composeTestRule.onNodeWithText("取消").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, normalSubmissions)
        assertEquals(0, claimSubmissions)
        assertNull(fixture.binding)
        assertEquals(before, fixture.snapshot())
        assertNull(controller.prompt.value)
    }

    @Test
    fun confirmUsesExactPreviewAndBoundOwnerACannotBeClaimedByB() = runBlocking {
        val fixture = SyntheticLegacyOutboxFixture()
        var normalSubmissions = 0
        var claimedPreview: UsageOutboxRecoveryPreview? = null
        val controller = LegacyOutboxClaimPromptController(
            preview = { fixture.preview() },
            submitNormal = { normalSubmissions++ },
            submitClaim = { preview ->
                claimedPreview = preview
                fixture.applyClaim(OWNER_A, preview.toRequest())
            }
        )
        controller.requestSync()
        showDialog(controller)

        composeTestRule.onNodeWithTag("legacy-outbox-claim-confirm").performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, normalSubmissions)
        assertEquals(fixture.preview(), claimedPreview)
        assertEquals(OWNER_A, fixture.binding)

        val ownerBAttempt = fixture.applyClaim(
            current = OWNER_B,
            claim = LegacyOutboxClaimRequest(OWNER_B.serverIdentity, OWNER_B.ownerId)
        )
        assertEquals(UsageOutboxBindingAction.BINDING_MISMATCH, ownerBAttempt.action)
        assertFalse(ownerBAttempt.shouldBind)
        assertEquals(OWNER_A, fixture.binding)
    }

    private fun showDialog(controller: LegacyOutboxClaimPromptController) {
        composeTestRule.setContent {
            val prompt by controller.prompt.collectAsState()
            PimTheme {
                prompt?.let { preview ->
                    LegacyOutboxClaimDialog(
                        preview = preview,
                        onConfirm = controller::confirm,
                        onDismiss = controller::dismiss
                    )
                }
            }
        }
        composeTestRule.onNodeWithTag("legacy-outbox-claim-dialog").assertIsDisplayed()
    }

    private data class SyntheticSnapshot(
        val binding: UsageOutboxOwnerBinding?,
        val rows: List<Pair<Long, String>>
    )

    private class SyntheticLegacyOutboxFixture {
        private val rows = List(PENDING_COUNT) { index ->
            val recordedAtUtc = FIXTURE_START_UTC +
                (FIXTURE_END_UTC - FIXTURE_START_UTC) * index / (PENDING_COUNT - 1)
            recordedAtUtc to "pending"
        }

        var binding: UsageOutboxOwnerBinding? = null
            private set

        fun preview(): UsageOutboxRecoveryPreview {
            val pendingRows = rows.filter { it.second != "synced" && it.second != "rejected" }
            return UsageOutboxRecoveryPreview(
                serverIdentity = OWNER_A.serverIdentity,
                ownerId = OWNER_A.ownerId,
                ownerName = "Owner A",
                pendingCount = pendingRows.size,
                earliestUtc = pendingRows.minOfOrNull { it.first },
                latestUtc = pendingRows.maxOfOrNull { it.first }
            )
        }

        fun applyClaim(
            current: UsageOutboxOwnerBinding,
            claim: LegacyOutboxClaimRequest
        ): UsageOutboxBindingDecision {
            val decision = resolveUsageOutboxBinding(
                stored = binding,
                current = current,
                pendingCount = preview().pendingCount,
                claim = claim
            )
            if (decision.shouldBind) {
                binding = current
            }
            return decision
        }

        fun snapshot(): SyntheticSnapshot = SyntheticSnapshot(binding, rows.toList())
    }

    private fun UsageOutboxRecoveryPreview.toRequest() =
        LegacyOutboxClaimRequest(serverIdentity, ownerId)

    companion object {
        private const val PENDING_COUNT = 42
        private val FIXTURE_START_UTC = Instant.parse("2026-10-01T03:04:00Z").toEpochMilli()
        private val FIXTURE_END_UTC = Instant.parse("2026-10-02T05:06:00Z").toEpochMilli()
        private val OWNER_A = UsageOutboxOwnerBinding(
            serverIdentity = "https://pim.example/api/v1/",
            ownerId = "11111111-1111-1111-1111-111111111111"
        )
        private val OWNER_B = UsageOutboxOwnerBinding(
            serverIdentity = "https://pim.example/api/v1/",
            ownerId = "22222222-2222-2222-2222-222222222222"
        )
        private val RANGE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun formatRange(earliestUtc: Long, latestUtc: Long): String {
            fun format(epochMillis: Long): String =
                RANGE_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
            return "${format(earliestUtc)} 至 ${format(latestUtc)}"
        }
    }
}
