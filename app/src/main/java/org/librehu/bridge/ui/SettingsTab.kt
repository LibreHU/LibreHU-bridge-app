package org.librehu.bridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.UnitInfo

/** A page of settings as `pages` lists it: id (`<package>:<page>`), title, icon. */
private data class PageRef(
    val id: String,
    val title: String,
    val icon: String,
) {
    val pkg get() = id.substringBefore(':')
}

/** One setting found by the search: its page and its label. */
private data class Hit(
    val page: PageRef,
    val label: String,
    val hint: String,
)

/**
 * Every settings page the head unit describes, LibreHU-service's first then each LibreHU app's (grouped under its
 * name), and a search through all their items: the pages are read once, when the first letter is typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(
    connected: Boolean,
    info: UnitInfo?,
    openPage: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    var pages by remember { mutableStateOf<List<PageRef>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var index by remember { mutableStateOf<List<Hit>?>(null) }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        pages =
            runCatching {
                (client.call("pages") as JsonArray).map { p ->
                    val o = p as JsonObject
                    PageRef(o.s("id"), o.s("title"), o.s("icon"))
                }
            }.getOrDefault(emptyList())
    }
    LaunchedEffect(query.isNotBlank(), pages) {
        if (query.isBlank() || index != null || pages.isEmpty()) return@LaunchedEffect
        index =
            pages.flatMap { p ->
                runCatching {
                    val o = client.call("page", buildJsonObject { put("id", p.id) }) as JsonObject
                    (o["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.s("type") != "section" }.map { Hit(p, it.s("label"), it.s("hint")) }
                }.getOrDefault(emptyList())
            }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, null) } },
                placeholder = { Text(stringResource(R.string.settings_search)) },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotBlank()) {
            val q = query.trim()
            val hits = index?.filter { it.label.contains(q, true) || it.hint.contains(q, true) || it.page.title.contains(q, true) }
            when {
                hits == null -> item { Text(stringResource(R.string.settings_indexing), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                hits.isEmpty() -> item { Text(stringResource(R.string.settings_nothing, q), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else ->
                    items(hits) { h ->
                        ListItem(
                            headlineContent = { Text(h.label) },
                            supportingContent = { Text("${appName(h.page.pkg)} › ${h.page.title}") },
                            leadingContent = { Icon(pageIcon(h.page.icon), null, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { openPage(h.page.id, h.page.title) },
                        )
                    }
            }
        } else {
            pages.groupBy { it.pkg }.forEach { (pkg, list) ->
                item { SectionTitle(appName(pkg)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
                        list.forEach { p -> PageTile(p.title, pageIcon(p.icon), Modifier.weight(1f)) { openPage(p.id, p.title) } }
                    }
                }
            }
            if (pages.isEmpty() && connected) item { Text(stringResource(R.string.settings_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Name of a LibreHU app from its package (the pages' prefix). */
private fun appName(pkg: String): String =
    when (pkg) {
        "org.librehu.service" -> "LibreHU-service"
        "org.librehu.launcher" -> "Launcher"
        "org.librehu.fm" -> "Radio FM"
        "org.librehu.dialer" -> "Dialer"
        "org.librehu.widgets" -> "Widgets"
        "org.librehu.btnremap" -> "BtnRemap"
        "org.librehu.easysource" -> "EasySource"
        "org.librehu.disc" -> "Disc"
        "org.librehu.ota" -> "LibreHU Apps"
        "org.librehu.helper" -> "Helper"
        "org.librehu.headunit" -> "Android Auto"
        "org.librehu.carplay" -> "CarPlay"
        "com.llsl.viper4android" -> "ViPER4Android"
        else -> pkg.substringAfterLast('.')
    }
