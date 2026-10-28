package com.pim.app.ui.root

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.pim.app.ui.location.LocationScreen
import com.pim.app.ui.schedule.SchedulePolicyScreen
import com.pim.app.ui.settings.SettingsScreen
import com.pim.app.ui.status.StatusCenterScreen
import com.pim.app.ui.theme.PimTheme
import com.pim.app.ui.today.TodayScreen
import com.pim.app.ui.tracks.TracksScreen

@Composable
fun PimRootScreen(initialDestination: PimDestination = PimDestination.Today) {
    var selected by rememberSaveable(initialDestination.name) { mutableStateOf(initialDestination) }
    var savedTracksUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // WO-ANDROID-POLICY-TRANSITION-20260928 REQ-3（D-10）：状态页写入失败告警的动作按钮
    // 要把用户带到设置页「策略切换历史」板块（不新建二级页面）。
    var scrollToPolicyHistory by rememberSaveable { mutableStateOf(false) }

    PimTheme {
      BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        if (selected in PimDestination.more) BackHandler { selected = PimDestination.More }
        if (selected == PimDestination.Notes || selected == PimDestination.More) BackHandler { selected = PimDestination.Today }
        Scaffold(
            bottomBar = {
              if (!wide) {
                PimBottomNavigation(
                    selected = selected,
                    onSelected = { selected = it }
                )
              }
            }
        ) { innerPadding ->
          Row(Modifier.fillMaxSize().padding(innerPadding)) {
            if (wide) PimSideNavigation(selected) { selected = it }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            val modifier = Modifier.fillMaxSize()
            when (selected) {
                PimDestination.Today -> TodayScreen(modifier, onOpenSettings = { selected = PimDestination.Settings })
                PimDestination.Notes -> com.pim.app.notes.NotesHomeScreen(modifier)
                PimDestination.More -> PimMoreScreen { selected = it }
                PimDestination.Location -> LocationScreen(
                    modifier = modifier,
                    onOpenSettings = { selected = PimDestination.Settings }
                )
                PimDestination.Tracks -> TracksScreen(
                    modifier = modifier,
                    onOpenSettings = { selected = PimDestination.Settings },
                    savedUrl = savedTracksUrl,
                    onUrlChanged = { savedTracksUrl = it }
                )
                PimDestination.Schedule -> SchedulePolicyScreen(
                    modifier = modifier,
                    onOpenSettings = { selected = PimDestination.Settings }
                )
                PimDestination.Status -> StatusCenterScreen(
                    modifier = modifier,
                    onOpenSettings = { selected = PimDestination.Settings },
                    onOpenPolicyTransitionHistory = {
                        scrollToPolicyHistory = true
                        selected = PimDestination.Settings
                    }
                )
                PimDestination.Settings -> SettingsScreen(
                    modifier = modifier,
                    scrollToPolicyTransitionHistory = scrollToPolicyHistory,
                    onPolicyTransitionHistoryScrolled = { scrollToPolicyHistory = false }
                )
            }
            }
          }
        }
      }
    }
}

@Composable
internal fun PimBottomNavigation(
    selected: PimDestination,
    onSelected: (PimDestination) -> Unit
) {
    NavigationBar(modifier = Modifier.testTag("pim-nav-bar")) {
        PimDestination.primary.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination || (destination == PimDestination.More && selected in PimDestination.more),
                onClick = { onSelected(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                modifier = Modifier.weight(1f).testTag("pim-nav-${destination.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun PimSideNavigation(selected: PimDestination, onSelected: (PimDestination) -> Unit) {
    NavigationRail(Modifier.fillMaxHeight().testTag("pim-nav-rail")) {
        Spacer(Modifier.height(PimTheme.dimensions.spaceLarge))
        PimDestination.primary.forEach { destination ->
            NavigationRailItem(
                selected = selected == destination || (destination == PimDestination.More && selected in PimDestination.more),
                onClick = { onSelected(destination) }, icon = { Icon(destination.icon, destination.label) },
                label = { Text(destination.label) }, modifier = Modifier.testTag("pim-nav-${destination.name.lowercase()}"))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PimMoreScreen(onSelected: (PimDestination) -> Unit) {
    val d = PimTheme.dimensions
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(d.spaceLarge), verticalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
        Text("更多", style = MaterialTheme.typography.headlineMedium)
        Text("定位、轨迹、日程和设备状态", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PimDestination.more.forEach { destination ->
            OutlinedCard(onClick = { onSelected(destination) }, modifier = Modifier.fillMaxWidth().testTag("pim-more-${destination.name.lowercase()}")) {
                Row(Modifier.padding(d.spaceLarge), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMedium)) {
                    Icon(destination.icon, null); Text(destination.label, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
