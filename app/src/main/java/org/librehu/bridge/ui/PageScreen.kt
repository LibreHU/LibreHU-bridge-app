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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    LaunchedEffect(pageId) {
        runCatching { client.call("page", buildJsonObject { put("id", pageId) }) as JsonObject }
            .onSuccess { p -> items = (p["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } }
            .onFailure { error = it.message ?: "" }
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
            }.onFailure { error = it.message ?: "" }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        items(items, key = { it.s("id") }) { item -> Item(item) { m, v -> send(m, item, v) } }
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
        "info" -> Column { Text(label, style = MaterialTheme.typography.labelLarge); Text(value?.contentOrNull ?: "—") }
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
                Text("$label : ${pos.toInt()} ${item.s("unit")}")
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (item["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { o ->
                        FilterChip(selected = value?.contentOrNull == o.s("v"), onClick = { send("set", JsonPrimitive(o.s("v"))) }, label = { Text(o.s("l")) })
                    }
                }
            }
        "button" -> {
            var ask by remember { mutableStateOf(false) }
            FilledTonalButton(onClick = { if ((item["confirm"] as? JsonPrimitive)?.booleanOrNull == true) ask = true else send("action", null) }) { Text(label) }
            if (ask) {
                AlertDialog(
                    onDismissRequest = { ask = false },
                    title = { Text(label) },
                    text = { Text(stringResource(R.string.confirm_action)) },
                    confirmButton = { TextButton(onClick = { ask = false; send("action", null) }) { Text(stringResource(android.R.string.ok)) } },
                    dismissButton = { TextButton(onClick = { ask = false }) { Text(stringResource(android.R.string.cancel)) } },
                )
            }
        }
        else -> Text(label)
    }
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
