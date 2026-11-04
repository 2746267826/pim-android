package com.pim.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pim.app.ui.theme.PimTheme

@Composable
internal fun NoteAutoSubmitControl(
    target: NoteUploadTarget?, authorization: NoteAutoSubmitAuthorization?,
    onEnable: (NoteUploadTarget) -> Unit, onRevoke: () -> Unit,
) {
    val d = PimTheme.dimensions
    var expanded by rememberSaveable { mutableStateOf(false) }
    val enabledForCurrent = authorization != null && target?.let {
        authorization.serverIdentity == it.binding.serverIdentity && authorization.ownerId == it.binding.ownerId
    } == true
    OutlinedCard(Modifier.fillMaxWidth().testTag("note-auto-submit-control")) {
        Column(Modifier.fillMaxWidth().padding(d.spaceMedium), verticalArrangement = Arrangement.spacedBy(d.spaceSmall)) {
            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "收起高级同步设置" else if (enabledForCurrent) "高级同步设置 · 自动上传已开启" else "高级同步设置")
            }
            if (expanded) {
                Text("完整材料自动上传", style = MaterialTheme.typography.titleSmall)
                Text(when {
                    enabledForCurrent -> "持续授权已开启。结束记录后会上传完整原始材料；不会自动发起付费转写或 AI 整理。撤销后，已入上传队列的任务仍继续。"
                    authorization != null -> "授权绑定在其他账号和服务器（${authorization.serverIdentity}），不会改投当前账号；切回原目标或撤销后再授权。"
                    else -> "默认仅保存在本机。开启后，之后固定的完整材料会上传到下方目标；转写和 AI 整理仍需逐项单独确认。"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (target == null) Text("请先登录并确认服务器。", color = MaterialTheme.colorScheme.error)
                else Text("目标：${target.name.ifBlank { "当前登录账号" }} · ${target.binding.serverIdentity}", style = MaterialTheme.typography.bodySmall)
                if (authorization != null) OutlinedButton(onClick = onRevoke, Modifier.fillMaxWidth().testTag("note-auto-submit-revoke")) {
                    Text("撤销持续授权")
                }
                if (target != null && !enabledForCurrent) Button(onClick = { onEnable(target) }, Modifier.fillMaxWidth().testTag("note-auto-submit-enable")) {
                    Text(if (authorization == null) "开启持续授权" else "切换授权到当前账号")
                }
            }
        }
    }
}
