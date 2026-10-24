package org.librehu.bridge.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.Remote
import org.librehu.bridge.data.Remote.bool
import org.librehu.bridge.data.Remote.str

/** An app of the head unit's launcher list. */
private class RemoteApp(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val icon: ImageBitmap?,
)

/** Apps of the head unit, read once per visit (icons included), kept while the app runs. */
private var cache: List<RemoteApp>? = null

/** The head unit's apps in a grid with their icons: search, system apps shown or not, a touch starts it there. */
@Composable
fun AppsTab() {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(cache) }
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var system by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        if (apps != null && reload == 0) return@LaunchedEffect
        error = ""
        runCatching { client.call("apps", timeoutMs = 60_000) as JsonArray }
            .onSuccess { a ->
                val list =
                    withContext(Dispatchers.Default) {
                        a.mapNotNull { it as? JsonObject }.map { o ->
                            val icon =
                                runCatching {
                                    val b = Base64.decode(o.str("icon"), Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
                                }.getOrNull()
                            RemoteApp(o.str("pkg"), o.str("label"), o.bool("system"), icon)
                        }
                    }
                cache = list
                apps = list
            }.onFailure { error = it.message ?: "" }
    }
    val shown = apps.orEmpty().filter { (system || !it.system) && (query.isBlank() || it.label.contains(query.trim(), true) || it.pkg.contains(query.trim(), true)) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(88.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
                    placeholder = { Text(stringResource(R.string.apps_search)) },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = system, onClick = { system = !system }, label = { Text(stringResource(R.string.apps_system)) })
                    Box(Modifier.weight(1f))
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
                }
                if (error.isNotEmpty()) StatusBanner(error, false)
                if (apps == null && error.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
        items(shown, key = { it.pkg }) { a ->
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        scope.launch {
                            runCatching { Remote.simple(client, "launch", "pkg" to a.pkg) }
                                .onSuccess { Toast.makeText(context, context.getString(R.string.apps_started, a.label), Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }
                    }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (a.icon != null) {
                    Image(a.icon, null, Modifier.size(56.dp))
                } else {
                    Icon(Icons.Default.Android, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(a.label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
