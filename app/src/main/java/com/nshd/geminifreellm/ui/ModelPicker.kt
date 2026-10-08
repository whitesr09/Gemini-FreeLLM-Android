package com.nshd.geminifreellm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.ChatViewModel
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(state: ChatViewModel.State, vm: ChatViewModel, onDismiss: () -> Unit, onManage: (Provider) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(state.settings.selected.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable(expanded) { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<Pair<Provider, String>?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val providers = remember(state.settings.selected, state.settings.profiles) {
        Provider.entries.sortedBy { if (it == state.settings.selected) 0 else if (state.settings.profiles.any { p -> p.provider == it }) 1 else 2 }
    }
    LaunchedEffect(expanded) { Provider.entries.firstOrNull { it.name == expanded }?.let(vm::loadCatalog) }
    fun select(provider: Provider, model: String) {
        if (selecting) return
        scope.launch {
            selecting = true
            if (vm.selectModel(provider, model)) onDismiss() else feedback = "Could not select this model. Check its ID, connection settings and key."
            selecting = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().widthIn(max = Design.readingWidth)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Design.page), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Choose your AI", style = MaterialTheme.typography.titleLarge)
                    Text("Switch models and keep this conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close model picker") }
            }
            Card(Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.small)) {
                Column(Modifier.padding(Design.medium)) {
                    TextButton(enabled = !selecting, onClick = { scope.launch {
                        selecting = true
                        if (vm.selectAuto()) onDismiss() else feedback = "Could not save Auto selection."
                        selecting = false
                    } }) { Text(if (state.settings.autoRouting) "✓ Auto · selected" else "Auto · choose for me") }
                    Text("Ranks configured models using availability, favorites and task fit. Tries up to 6 models before any reply starts. Chat and agent context may go to multiple enabled providers; normal API charges apply.", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Design.page, vertical = Design.small),
                placeholder = { Text("Search models in expanded provider") }, singleLine = true, shape = Design.card,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear model search") }
                })
            Row(Modifier.padding(horizontal = Design.page), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null, Modifier.size(18.dp)) })
                Spacer(Modifier.width(Design.small))
                Text("Catalog ≠ account quota", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            feedback?.let { Text(it, Modifier.padding(horizontal = Design.page), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f).semantics { contentDescription = "Provider model list" }, contentPadding = PaddingValues(Design.page), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                item { Text("Access reflects your last request. Free-tier expiry and remaining quota are unknown unless the provider reports them. Sending shares this chat with the chosen provider.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                providers.forEach { provider ->
                    val open = expanded == provider.name
                    val profile = state.settings.profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)
                    val catalog = state.catalogs[provider] ?: ModelCatalog()
                    item(key = provider.name) {
                        Surface(shape = Design.card, color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().clickable { expanded = if (open) "" else provider.name }.padding(Design.medium)
                                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(provider.label, style = MaterialTheme.typography.titleMedium)
                                    Text(if (catalog.models.isNotEmpty()) "${catalog.models.size} models" else if (profile.apiKey.isNotBlank()) "Key configured" else "Configure or browse",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!state.settings.autoRouting && state.settings.selected == provider) Icon(Icons.Outlined.CheckCircleOutline, "Active provider", Modifier.padding(end = Design.small))
                                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                            }
                        }
                    }
                    if (open) {
                        item(key = "controls/${provider.name}") {
                            Column {
                                if (state.settings.profiles.any { it.provider == provider }) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Allow in Auto", Modifier.weight(1f))
                                    Switch(provider !in state.settings.autoExcluded, { enabled -> scope.launch { vm.setAutoProvider(provider, enabled) } })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                                    TextButton(onClick = { vm.loadCatalog(provider, true) }, enabled = !catalog.loading) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Text("Refresh") }
                                    TextButton(onClick = { onManage(provider) }) { Text("Connection settings") }
                                }
                                if (catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading model catalog" })
                                catalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                if (catalog.fetchedAt > 0) Text("Catalog updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(catalog.fetchedAt))}", style = MaterialTheme.typography.labelSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(manual, { manual = it.take(200) }, Modifier.weight(1f), singleLine = true,
                                        label = { Text("Enter model ID manually") }, shape = Design.card)
                                    IconButton(onClick = { select(provider, manual) }, enabled = manual.isNotBlank() && !selecting) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Use manual model ID") }
                                }
                            }
                        }
                        val listed = (catalog.models + if (profile.model.isNotBlank() && catalog.models.none { it.id == profile.model }) listOf(ModelInfo(profile.model)) else emptyList())
                            .filter { (!favoritesOnly || modelKey(provider, it.id) in state.settings.favorites) && (it.id.contains(query, true) || it.name.contains(query, true)) }
                            .sortedWith(compareByDescending<ModelInfo> { modelKey(provider, it.id) in state.settings.favorites }.thenBy { it.id })
                        if (!catalog.loading && listed.isEmpty()) item(key = "empty/${provider.name}") { Text("No matching models. Refresh, change the search or enter an ID.", style = MaterialTheme.typography.bodyMedium) }
                        items(listed, key = { "${provider.name}/${it.id}" }, contentType = { "model" }) { model ->
                            val key = modelKey(provider, model.id)
                            val active = !state.settings.autoRouting && state.settings.selected == provider && profile.model == model.id
                            val access = state.access[key]
                            Surface(shape = Design.card, color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().clickable(enabled = !selecting) { select(provider, model.id) }
                                    .padding(Design.medium).semantics { selected = active }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(model.id, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        if (active) Icon(Icons.Outlined.Check, "Selected model", Modifier.size(20.dp))
                                        IconButton(onClick = { vm.favorite(provider, model.id) }) {
                                            Icon(if (key in state.settings.favorites) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                                                if (key in state.settings.favorites) "Remove favorite ${model.id}" else "Favorite ${model.id}")
                                        }
                                    }
                                    Text(access?.let { "${it.state.label} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.checkedAt))}" } ?: "Access unverified",
                                        style = MaterialTheme.typography.labelSmall, color = if (access != null && access.state !in listOf(AccessState.USABLE, AccessState.UNKNOWN)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (model.contextTokens != null) Text("${model.contextTokens} context tokens", style = MaterialTheme.typography.labelSmall)
                                    if (model.freePricing != null) Text(if (model.freePricing) "Catalog lists free pricing · quota unknown" else "Catalog lists paid pricing", style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { check = provider to model.id }, enabled = state.checking.isEmpty()) {
                                        Text(if (key in state.checking) "Checking access…" else "Check access")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    check?.let { target -> AlertDialog(onDismissRequest = { check = null }, title = { Text("Check model access?") },
        text = { Text("Send a short ‘Reply with OK only’ request to ${target.first.label} / ${target.second}. This may use quota or incur a charge. Your chat is not included.") },
        confirmButton = { TextButton(onClick = { vm.checkModel(target.first, target.second); check = null }) { Text("Check access") } },
        dismissButton = { TextButton(onClick = { check = null }) { Text("Cancel") } }) }
}
