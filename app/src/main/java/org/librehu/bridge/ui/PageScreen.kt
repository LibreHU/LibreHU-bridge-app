package org.librehu.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient

/**
 * One page of settings as the head unit describes it: each item drawn from its type (section, info, switch, slider,
 * choice, button), each change sent at once (`set` / `action`) and the item redrawn from the answer.
 */
@Composable
fun PageScreen(pageId: String) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(pageId, revision) {
        runCatching { client.call("page", buildJsonObject { put("id", pageId) }, 30_000) as JsonObject }
            .onSuccess { p ->
                items = (p["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                error = ""
            }.onFailure { error = it.message ?: "" }
    }

    fun send(
        method: String,
        item: JsonObject,
        value: JsonElement?,
    ) {
        scope.launch {
            runCatching {
                client.call(
                    method,
                    buildJsonObject {
                        put("page", pageId)
                        put("item", item.s("id"))
                        if (value != null) put("value", value)
                    },
                )
            }.onSuccess { r ->
                (r as? JsonObject)?.let { updated -> items = items.map { if (it.s("id") == updated.s("id")) updated else it } }
                // The whole page again: options that depend on this one (a mode, an on / off) appear or go.
                revision++
            }.onFailure { error = it.message ?: "" }
        }
    }
    // Items grouped by their sections, one card per group
    val groups =
        buildList<Pair<String, MutableList<JsonObject>>> {
            for (it in items) {
                if (it.s("type") == "section") {
                    add(it.s("label") to mutableListOf())
                } else {
                    if (isEmpty()) add("" to mutableListOf())
                    last().second += it
                }
            }
        }.filter { it.second.isNotEmpty() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error.isNotEmpty()) item { StatusBanner(error, false) }
        if (items.isEmpty() && error.isEmpty()) {
            item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        groups.forEachIndexed { i, (title, list) ->
            if (title.isNotEmpty()) item(key = "s$i") { SectionTitle(title) }
            item(key = "g$i") {
                androidx.compose.material3.Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (item in list) Item(item) { m, v -> send(m, item, v) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Item(
    item: JsonObject,
    send: (String, JsonElement?) -> Unit,
) {
    val label = item.s("label")
    val value = item["value"] as? JsonPrimitive
    val hint = item.s("hint")
    when (item.s("type")) {
        "section" -> Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        "info" ->
            if (value?.contentOrNull.isNullOrEmpty()) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value?.contentOrNull.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        "text" -> {
            var editing by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { editing = true },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    Text(value?.contentOrNull?.ifEmpty { "—" } ?: "—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editing) {
                val max = (item["max"] as? JsonPrimitive)?.intOrNull ?: 64
                var text by remember { mutableStateOf(value?.contentOrNull.orEmpty()) }
                AlertDialog(
                    onDismissRequest = { editing = false },
                    title = { Text(label) },
                    text = { OutlinedTextField(value = text, onValueChange = { text = it.take(max) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                    confirmButton = {
                        TextButton(onClick = {
                            editing = false
                            send("set", JsonPrimitive(text))
                        }) { Text(stringResource(android.R.string.ok)) }
                    },
                    dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
        }
        "switch" ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = value?.booleanOrNull == true, onCheckedChange = { send("set", JsonPrimitive(it)) })
            }
        "slider" -> {
            val min = (item["min"] as? JsonPrimitive)?.intOrNull ?: 0
            val max = (item["max"] as? JsonPrimitive)?.intOrNull ?: 100
            val step = ((item["step"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1)
            var pos by remember(value) { mutableFloatStateOf((value?.intOrNull ?: min).toFloat()) }
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    // As the head unit writes it ("+4 dB", "2.5 s"), or the raw number while it moves.
                    val shown = item.s("display").takeIf { it.isNotEmpty() && pos.toInt() == value?.intOrNull } ?: "${pos.toInt()} ${item.s("unit")}".trim()
                    Text(shown, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = pos,
                    onValueChange = { pos = (Math.round(it / step) * step).toFloat() },
                    onValueChangeFinished = { send("set", JsonPrimitive(pos.toInt())) },
                    valueRange = min.toFloat()..max.toFloat(),
                    steps = ((max - min) / step - 1).coerceIn(0, 200),
                )
            }
        }
        "choice" ->
            Column {
                Text(label)
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (item["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { o ->
                        FilterChip(selected = value?.contentOrNull == o.s("v"), onClick = { send("set", JsonPrimitive(o.s("v"))) }, label = { Text(o.s("l")) })
                    }
                }
            }
        "button" -> {
            var ask by remember { mutableStateOf(false) }
            Column {
                FilledTonalButton(onClick = { if ((item["confirm"] as? JsonPrimitive)?.booleanOrNull == true) ask = true else send("action", null) }) { Text(label) }
                if (hint.isNotEmpty() && (item["confirm"] as? JsonPrimitive)?.booleanOrNull != true) Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            if (ask) {
                AlertDialog(
                    onDismissRequest = { ask = false },
                    title = { Text(label) },
                    text = { Text(hint.ifEmpty { stringResource(R.string.confirm_action) }) },
                    confirmButton = { TextButton(onClick = { ask = false; send("action", null) }) { Text(stringResource(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = { ask = false }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
        }
        else -> Text(label)
    }
}

