package com.nshd.geminifreellm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nshd.geminifreellm.ChatViewModel
import com.nshd.geminifreellm.data.AutoRouter
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ColumnScope.AutoModelsPanel(state: ChatViewModel.State, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val models = remember(state.settings.profiles, state.catalogs) { AutoRouter.models(state.settings, state.catalogs) }
    val filtered = remember(models, query) { models.filter { it.model.contains(query, true) || it.provider.label.contains(query, true) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    LazyColumn(Modifier.weight(1f).semantics { contentDescription = "Auto model pool" },
        contentPadding = PaddingValues(Design.page), verticalArrangement = Arrangement.spacedBy(Design.small)) {
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto", style = MaterialTheme.typography.headlineSmall)
                        Text("All configured providers", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.settings.autoRouting, enabled = !saving && state.generatingId == null,
                        modifier = Modifier.semantics { contentDescription = "Global Auto mode" },
                        onCheckedChange = { enabled -> scope.launch { saving = true; vm.setAutoEnabled(enabled); saving = false } })
                }
                Text(if (state.settings.autoRouting) "On · automatically checks, selects and switches models" else "Off · using ${state.settings.active.model}", style = MaterialTheme.typography.titleSmall)
                Text("${models.size} models from ${state.settings.profiles.size} configured providers", style = MaterialTheme.typography.bodyMedium)
                Text("Turning Auto on sends short availability checks (up to 6). Normal API charges apply. Chats and agent context can go to any enabled provider. Failed replies automatically switch; partial replies are preserved separately.", style = MaterialTheme.typography.bodySmall)
                state.autoChoice?.let { Text("Ready model: $it", style = MaterialTheme.typography.titleSmall) }
                Text(state.autoStatus, style = MaterialTheme.typography.bodySmall)
                if (state.autoChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (state.settings.autoRouting) TextButton(enabled = state.generatingId == null && state.checking.isEmpty(), onClick = vm::checkAutoModels) { Text("Recheck availability") }
                if (state.settings.autoRouting) TextButton(onClick = onDismiss) { Text("Back to chat") }
            } }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Provider pool", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !state.autoChecking && state.catalogs.values.none { it.loading }, onClick = { vm.refreshAutoCatalogs(true) }) { Text("Refresh models") }
            }
        }
        items(state.settings.profiles, key = { "provider/${it.provider}" }) { profile ->
            val catalog = state.catalogs[profile.provider]
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.provider.label, Modifier.weight(1f))
                    Switch(profile.provider !in state.settings.autoExcluded, enabled = !state.autoChecking,
                        onCheckedChange = { enabled -> scope.launch { vm.setAutoProvider(profile.provider, enabled) } })
                }
                if (catalog?.loading == true) Text("Discovering models…", style = MaterialTheme.typography.bodySmall)
                val issue = FreeLlmApiClient.validate(profile, requireModel = false) ?: catalog?.error
                issue?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search all models and providers") })
            Text("Usable means a recent request succeeded; access can change. Unchecked models have not been verified. Account quota and free-tier expiry are not assumed.", style = MaterialTheme.typography.bodySmall)
        }
        if (filtered.isEmpty()) item { Text("No models found. Configure a provider in Settings or refresh its catalog.") }
        items(filtered, key = { modelKey(it.provider, it.model) }, contentType = { "auto-model" }) { profile ->
            val key = modelKey(profile.provider, profile.model)
            val access = state.access[key]
            val providerBlocked = state.access.any { (otherKey, value) ->
                otherKey.startsWith("${profile.provider.name}/") && value.state in listOf(AccessState.AUTH, AccessState.QUOTA) && now - value.checkedAt < AutoRouter.cooldown(value.state)
            }
            val status = when {
                key in state.checking -> "Checking…"
                profile.provider in state.settings.autoExcluded -> "Excluded from Auto"
                !AutoRouter.isTextModel(profile.model) -> "Not a text chat model"
                FreeLlmApiClient.validate(profile) != null -> "Connection needs configuration"
                providerBlocked -> "Provider key / account quota cooldown"
                access == null || access.state == AccessState.UNKNOWN -> "Unchecked"
                access.state == AccessState.USABLE -> "Usable · last request succeeded"
                now - access.checkedAt < AutoRouter.cooldown(access.state) -> "${access.state.label} · cooling down"
                else -> "${access.state.label} · eligible to recheck"
            }
            Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(Design.medium)) {
                    Text(profile.model, style = MaterialTheme.typography.titleSmall)
                    Text(profile.provider.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    access?.takeIf { it.latencyMs > 0 }?.let { Text("Last response: %.1fs".format(it.latencyMs / 1000.0), style = MaterialTheme.typography.labelSmall) }
                    access?.let { Text("Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.checkedAt))}", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
