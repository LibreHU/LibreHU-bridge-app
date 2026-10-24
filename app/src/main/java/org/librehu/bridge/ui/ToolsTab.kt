package org.librehu.bridge.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.HeadUnit
import org.librehu.bridge.data.Remote
import org.librehu.bridge.data.Remote.str
import org.librehu.bridge.data.UnitInfo

/**
 * Tools of the head unit: restart (Android, recovery, fastboot, each confirmed), logs fetched to the phone (logcat,
 * kernel, LibreHU's lines), the advanced editor of Android's settings (every key of system / secure / global, as
 * root; the usual ones are on the Head unit page of the settings), and what the head unit's service is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsTab(
    info: UnitInfo?,
    unit: HeadUnit,
) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    var confirmReboot by remember { mutableStateOf<String?>(null) }
    var logBusy by remember { mutableStateOf<String?>(null) }
    var logPath by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val saveLog =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            val path = logPath ?: return@rememberLauncherForActivityResult
            logPath = null
            if (uri == null) {
                logBusy = null
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)!!.use { out -> Remote.download(client, path, out) { _, _ -> } }
                    }
                }.onSuccess { toast(context.getString(R.string.logs_saved)) }.onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                logBusy = null
            }
        }

    fun fetchLog(kind: String) {
        logBusy = kind
        scope.launch {
            runCatching { Remote.simple(client, "logs", "kind" to kind, timeoutMs = 150_000) as JsonObject }
                .onSuccess { r ->
                    logPath = r.str("path")
                    saveLog.launch(r.str("path").substringAfterLast('/'))
                }.onFailure {
                    logBusy = null
                    toast(context.getString(R.string.files_failed, it.message ?: ""))
                }
        }
    }

    if (editor) {
        AndroidSettingsEditor { editor = false }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (info != null && !info.root) item { StatusBanner(stringResource(R.string.tools_no_root), false) }
        item { SectionTitle(stringResource(R.string.tools_restart)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 3) {
                ToolTile(Icons.Default.RestartAlt, stringResource(R.string.tools_reboot), Modifier.weight(1f)) { confirmReboot = "normal" }
                ToolTile(Icons.Default.SettingsBackupRestore, stringResource(R.string.tools_recovery), Modifier.weight(1f)) { confirmReboot = "recovery" }
                ToolTile(Icons.Default.DeveloperBoard, stringResource(R.string.tools_fastboot), Modifier.weight(1f)) { confirmReboot = "bootloader" }
            }
        }
        if (info?.has("logs") == true) {
            item { SectionTitle(stringResource(R.string.tools_logs)) }
            item {
                Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.tools_logs_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((kind, label) in listOf("librehu" to R.string.logs_librehu, "logcat" to R.string.logs_logcat, "kernel" to R.string.logs_kernel)) {
                                FilledTonalButton(enabled = logBusy == null, onClick = { fetchLog(kind) }) {
                                    Icon(Icons.Default.Article, null)
                                    Text(stringResource(label))
                                }
                            }
                        }
                        if (logBusy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (info?.has("settings") == true) {
            item { SectionTitle(stringResource(R.string.tools_android)) }
            item {
                ElevatedCard(onClick = { editor = true }, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.tools_android_editor)) },
                        supportingContent = { Text(stringResource(R.string.tools_android_editor_hint)) },
                        leadingContent = { Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        item { SectionTitle(stringResource(R.string.tools_about)) }
        item {
            Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    for ((k, v) in listOf(
                        stringResource(R.string.about_unit) to unit.name,
                        stringResource(R.string.about_fp) to unit.fingerprint.chunked(4).take(8).joinToString(" "),
                        stringResource(R.string.about_api) to "${info?.api ?: "?"} · bridge ${info?.bridge ?: "?"}",
                        stringResource(R.string.about_root) to stringResource(if (info?.root == true) R.string.yes else R.string.no),
                        stringResource(R.string.about_root_files) to stringResource(if (info?.rootFiles == true) R.string.yes else R.string.no),
                    )) {
                        ListItem(
                            headlineContent = { Text(k) },
                            supportingContent = { Text(v, fontFamily = FontFamily.Monospace) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
        if (info != null && info.bridge < 2) item { StatusBanner(stringResource(R.string.update_service), null) }
    }

    confirmReboot?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmReboot = null },
            icon = { Icon(Icons.Default.RestartAlt, null) },
            title = {
                Text(
                    stringResource(
                        when (mode) {
                            "recovery" -> R.string.tools_recovery
                            "bootloader" -> R.string.tools_fastboot
                            else -> R.string.tools_reboot
                        },
                    ),
                )
            },
            text = { Text(stringResource(if (mode == "bootloader") R.string.tools_fastboot_hint else R.string.tools_reboot_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReboot = null
                    scope.launch {
                        runCatching { Remote.simple(client, "reboot", "mode" to mode) }
                            .onSuccess { toast(context.getString(R.string.tools_rebooting)) }
                            .onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                    }
                }) { Text(stringResource(R.string.tools_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReboot = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun ToolTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Every key of Android's settings (system, secure, global) as root: search, change, add, delete. */
@Composable
private fun AndroidSettingsEditor(close: () -> Unit) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    val spaces = listOf("system", "secure", "global")
    var ns by remember { mutableStateOf("system") }
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler { close() }
    LaunchedEffect(ns, reload) {
        rows = null
        error = ""
        runCatching { (Remote.simple(client, "settings.list", "ns" to ns) as JsonArray).mapNotNull { it as? JsonObject }.map { it.str("key") to it.str("value") } }
            .onSuccess { rows = it }
            .onFailure { error = it.message ?: "" }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Default.Close, null) }
                Text(stringResource(R.string.tools_android_editor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = "" to "" }) { Icon(Icons.Default.Add, stringResource(R.string.editor_add)) }
            }
        }
        item { StatusBanner(stringResource(R.string.editor_warning), null) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                spaces.forEachIndexed { i, s ->
                    SegmentedButton(selected = ns == s, onClick = { ns = s }, shape = SegmentedButtonDefaults.itemShape(i, spaces.size)) { Text(s) }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(stringResource(R.string.editor_search)) },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error.isNotEmpty()) item { StatusBanner(error, false) }
        if (rows == null && error.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val shown = rows.orEmpty().filter { query.isBlank() || it.first.contains(query.trim(), true) || it.second.contains(query.trim(), true) }
        items(shown, key = { it.first }) { (k, v) ->
            ListItem(
                headlineContent = { Text(k, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(v.ifEmpty { "∅" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { editing = k to v },
            )
        }
    }
    editing?.let { (k0, v0) ->
        var key by remember(k0) { mutableStateOf(k0) }
        var value by remember(k0) { mutableStateOf(v0) }
        AlertDialog(
            onDismissRequest = { editing = null },
            icon = { Icon(Icons.Default.SystemUpdateAlt, null) },
            title = { Text("$ns ${k0.ifEmpty { stringResource(R.string.editor_new) }}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (k0.isEmpty()) OutlinedTextField(value = key, onValueChange = { key = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.editor_key)) })
                    OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text(stringResource(R.string.editor_value)) })
                }
            },
            confirmButton = {
                TextButton(enabled = key.isNotBlank(), onClick = {
                    editing = null
                    scope.launch {
                        runCatching { Remote.simple(client, "settings.put", "ns" to ns, "key" to key, "value" to value) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        reload++
                    }
                }) { Text(stringResource(R.string.editor_save)) }
            },
            dismissButton = {
                Row {
                    if (k0.isNotEmpty()) {
                        OutlinedButton(onClick = {
                            editing = null
                            scope.launch {
                                runCatching { Remote.simple(client, "settings.delete", "ns" to ns, "key" to k0) }
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                reload++
                            }
                        }) { Text(stringResource(R.string.files_delete)) }
                    }
                    TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) }
                }
            },
        )
    }
}
