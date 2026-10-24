package org.librehu.bridge.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.HeadUnit
import org.librehu.bridge.data.LinkState
import org.librehu.bridge.data.Remote
import org.librehu.bridge.data.UnitInfo

/** The five parts of a head unit's screen, in the bottom bar. */
enum class UnitTab(
    val label: Int,
    val icon: ImageVector,
    /** `hello` feature needed (bridge level 2), null for every head unit. */
    val feature: String?,
) {
    HOME(R.string.tab_home, Icons.Default.Speed, null),
    SETTINGS(R.string.tab_settings, Icons.Default.Tune, null),
    APPS(R.string.tab_apps, Icons.Default.Apps, "apps"),
    FILES(R.string.tab_files, Icons.Default.Folder, "files"),
    TOOLS(R.string.tab_tools, Icons.Default.Build, "reboot"),
}

/** Tab shown, kept while a settings page is open over the head unit's screen. */
private var currentTab by mutableStateOf(UnitTab.HOME)

/**
 * One head unit: connected on opening (Wi-Fi, else Bluetooth), what its service offers read once (`hello`), then the
 * dashboard, the settings of the head unit and of every LibreHU app (searchable), its apps, its files, and the tools
 * (reboots, logs, Android settings). An older LibreHU-service (bridge level 1) has the first two only, with a line
 * saying an update brings the rest.
 */
@Composable
fun UnitScreen(
    unit: HeadUnit,
    openPage: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val state by client.state.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf<UnitInfo?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(unit.fingerprint, attempt) {
        if (client.connect(unit)) info = runCatching { Remote.hello(client) }.getOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        when (val s = state) {
            is LinkState.Connecting -> StatusBanner(stringResource(R.string.connecting, s.name), null, busy = true, modifier = Modifier.padding(16.dp))
            is LinkState.Failed ->
                StatusBanner(stringResource(R.string.link_failed, s.message), false, modifier = Modifier.padding(16.dp)) {
                    IconButton(onClick = { attempt++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
                }
            else -> {}
        }
        val tabs = UnitTab.entries.filter { it.feature == null || info?.has(it.feature) != false }
        if (currentTab !in tabs) currentTab = UnitTab.HOME
        Box(Modifier.weight(1f)) {
            Crossfade(currentTab, label = "tab") { t ->
                when (t) {
                    UnitTab.HOME -> Dashboard(info, state is LinkState.Connected)
                    UnitTab.SETTINGS -> SettingsTab(state is LinkState.Connected, info, openPage)
                    UnitTab.APPS -> AppsTab()
                    UnitTab.FILES -> FilesTab(info)
                    UnitTab.TOOLS -> ToolsTab(info, unit)
                }
            }
        }
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            tabs.forEach { t ->
                NavigationBarItem(
                    selected = currentTab == t,
                    onClick = { currentTab = t },
                    icon = { Icon(t.icon, null) },
                    label = { Text(stringResource(t.label)) },
                )
            }
        }
    }
}
