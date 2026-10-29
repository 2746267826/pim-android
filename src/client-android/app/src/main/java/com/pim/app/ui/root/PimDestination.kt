package com.pim.app.ui.root

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector

enum class PimDestination(
    val label: String,
    val icon: ImageVector
) {
    Today("今日", Icons.Filled.LocationOn),
    Notes("笔记", Icons.Filled.EditNote),
    More("更多", Icons.Filled.MoreHoriz),
    Location("定位", Icons.Filled.MyLocation),
    Tracks("轨迹", Icons.Filled.LocationOn),
    Schedule("日程", Icons.Filled.CheckCircle),
    Status("状态", Icons.Filled.Security),
    Settings("设置", Icons.Filled.Settings);

    companion object {
        val primary = listOf(Today, Notes, More, Settings)
        val more = listOf(Location, Tracks, Schedule, Status)
    }
}
