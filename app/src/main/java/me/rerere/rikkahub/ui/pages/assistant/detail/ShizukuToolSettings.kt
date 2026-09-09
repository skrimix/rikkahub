package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.shizuku.ShizukuShellManager
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
internal fun ShizukuToolSettings(assistant: Assistant, onUpdate: (Assistant) -> Unit) {
    val manager: ShizukuShellManager = koinInject()
    val status by manager.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val enabled = LocalToolOption.ShizukuShell in assistant.localTools

    LaunchedEffect(manager, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Shizuku has no separate callback for permission revoked in its manager app.
                manager.refreshStatus()
                delay(2000)
            }
        }
    }
    CardGroup {
        item(
            headlineContent = { Text("Device shell (Shizuku)") },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Run commands on this device with Shizuku's ADB or root privileges. Commands can read or change device data with those privileges.")
                    Text(status.message)
                    Row {
                        if (!status.permissionGranted) {
                            TextButton(
                                onClick = manager::requestPermission,
                                enabled = status.supported && !status.permissionDeniedPermanently,
                            ) { Text("Grant permission") }
                        }
                        TextButton(onClick = { uriHandler.openUri("https://shizuku.rikka.app/guide/setup/") }) {
                            Text("Shizuku setup")
                        }
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        val tools = assistant.localTools - LocalToolOption.ShizukuShell
                        onUpdate(assistant.copy(localTools = if (checked) tools + LocalToolOption.ShizukuShell else tools))
                    },
                )
            },
        )
        if (enabled) {
            item(
                headlineContent = { Text("Require command approval") },
                supportingContent = { Text("Ask before each device shell command. When off, this assistant can run commands automatically.") },
                trailingContent = {
                    Switch(
                        checked = assistant.shizukuShellRequiresApproval,
                        onCheckedChange = { onUpdate(assistant.copy(shizukuShellRequiresApproval = it)) },
                    )
                },
            )
        }
    }
}
