package com.pim.app.ui.permissions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.permissions.PermissionStatusRepository
import com.pim.app.status.CollectionPermissionAccess
import com.pim.app.status.PermissionStatusSnapshot
import com.pim.app.status.StatusPermissionNavigator
import com.pim.app.status.collectionPermissions

@Composable
fun PermissionCenterScreen(
    modifier: Modifier = Modifier,
    uploadQueueCount: Int = 0,
    collectionQuality: String = "collection quality: waiting",
    snapshotProvider: (() -> PermissionStatusSnapshot)? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repo = remember(appContext) { PermissionStatusRepository(appContext, UsageAccessChecker(appContext)) }
    val readSnapshot = remember(repo, snapshotProvider) { snapshotProvider ?: repo::snapshot }
    var snapshot by remember(readSnapshot) { mutableStateOf(readSnapshot()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, readSnapshot) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = readSnapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val rows = permissionSettingRows(snapshot)
    val collectionStates = snapshot.collectionPermissions().associateBy { it.kind.issueCode }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "权限中心",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        rows.forEach { row ->
            val collectionState = collectionStates[row.issueCode]
            val statusText = collectionState?.let { state ->
                when (state.access) {
                    CollectionPermissionAccess.GRANTED -> "已授权"
                    CollectionPermissionAccess.MISSING -> "未授权"
                    CollectionPermissionAccess.RESTRICTED,
                    CollectionPermissionAccess.CHECK_FAILED -> state.statusText
                }
            } ?: if (row.granted) "已授权" else "未授权"
            val statusColor = when (collectionState?.access) {
                CollectionPermissionAccess.GRANTED -> MaterialTheme.colorScheme.primary
                CollectionPermissionAccess.RESTRICTED -> MaterialTheme.colorScheme.tertiary
                CollectionPermissionAccess.MISSING,
                CollectionPermissionAccess.CHECK_FAILED -> MaterialTheme.colorScheme.error
                null -> if (row.granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            }
            PermissionSettingRowItem(
                row = row,
                statusText = statusText,
                statusColor = statusColor,
                onClick = { StatusPermissionNavigator.open(context, row.issueCode) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "上传队列", fontWeight = FontWeight.Medium)
                Text(text = "$uploadQueueCount 条")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "采集质量", fontWeight = FontWeight.Medium)
                Text(text = collectionQuality)
            }
        }
    }
}

@Composable
private fun PermissionSettingRowItem(
    row: PermissionSettingRow,
    statusText: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = row.title, fontWeight = FontWeight.Medium)
                Text(
                    text = if (row.isHardBlock) "采集必需" else "建议",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = statusText,
                modifier = Modifier.testTag("permission-center-status-${row.issueCode}"),
                color = statusColor
            )
        }
    }
}
