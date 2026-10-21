package org.librehu.bridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.HeadUnit
import org.librehu.bridge.data.LinkState
import org.librehu.bridge.data.PairingCode
import org.librehu.bridge.data.Units

/** Where the app is: the paired head units, one head unit, or one of its pages. */
private sealed class Nav {
    data object Home : Nav()

    data class Unit(
        val unit: HeadUnit,
    ) : Nav()

    data class Page(
        val unit: HeadUnit,
        val id: String,
        val title: String,
    ) : Nav()
}

/** LibreHU's phone app: pairs with head units (QR code or Bluetooth) and shows what their service describes. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val scheme =
                when {
                    Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                    Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                    dark -> darkColorScheme()
                    else -> lightColorScheme()
                }
            MaterialTheme(colorScheme = scheme) { App() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    var nav by remember { mutableStateOf<Nav>(Nav.Home) }
    BackHandler(nav != Nav.Home) {
        nav =
            when (val n = nav) {
                is Nav.Page -> Nav.Unit(n.unit)
                else -> Nav.Home
            }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val n = nav) {
                            Nav.Home -> stringResource(R.string.app_name)
                            is Nav.Unit -> n.unit.name
                            is Nav.Page -> n.title
                        },
                    )
                },
                navigationIcon = {
                    if (nav != Nav.Home) {
                        IconButton(onClick = { nav = (nav as? Nav.Page)?.let { Nav.Unit(it.unit) } ?: Nav.Home }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val n = nav) {
                Nav.Home -> HomeScreen { nav = Nav.Unit(it) }
                is Nav.Unit -> UnitScreen(n.unit) { id, title -> nav = Nav.Page(n.unit, id, title) }
                is Nav.Page -> PageScreen(n.id)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun HomeScreen(open: (HeadUnit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = BridgeClient.get(context)
    val units by Units.get(context).units.collectAsStateWithLifecycle()
    val state by client.state.collectAsStateWithLifecycle()
    var btList by remember { mutableStateOf<List<BluetoothDevice>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun paired(result: Result<HeadUnit>) {
        busy = false
        result
            .onSuccess { open(it) }
            .onFailure { Toast.makeText(context, context.getString(R.string.pair_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
    }
    val scan =
        androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
            val code = r.contents?.let { PairingCode.parse(it) }
            if (r.contents != null && code == null) Toast.makeText(context, R.string.qr_not_librehu, Toast.LENGTH_LONG).show()
            if (code != null) {
                busy = true
                scope.launch { paired(runCatching { client.pair(code) }) }
            }
        }
    val btPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) btList = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.home_hint), style = MaterialTheme.typography.bodyMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = !busy, onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(context.getString(R.string.scan_prompt)).setBeepEnabled(false))
                }) {
                    Icon(Icons.Default.QrCodeScanner, null)
                    Text("  " + stringResource(R.string.pair_qr))
                }
                FilledTonalButton(enabled = !busy, onClick = {
                    if (Build.VERSION.SDK_INT >= 31 &&
                        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    ) {
                        btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        btList = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
                    }
                }) {
                    Icon(Icons.Default.Bluetooth, null)
                    Text("  " + stringResource(R.string.pair_bt))
                }
            }
        }
        (state as? LinkState.Connecting)?.let { item { Text(stringResource(R.string.connecting, it.name)) } }
        (state as? LinkState.Failed)?.let { item { Text(stringResource(R.string.link_failed, it.message), color = MaterialTheme.colorScheme.error) } }
        items(units, key = { it.fingerprint }) { u ->
            Card(Modifier.fillMaxWidth().clickable { open(u) }) {
                ListItem(
                    headlineContent = { Text(u.name) },
                    supportingContent = { Text(stringResource(R.string.unit_fp, u.fingerprint.take(8))) },
                    trailingContent = {
                        IconButton(onClick = { Units.get(context).remove(u.fingerprint) }) { Icon(Icons.Default.Delete, null) }
                    },
                )
            }
        }
    }
    btList?.let { list ->
        AlertDialog(
            onDismissRequest = { btList = null },
            title = { Text(stringResource(R.string.pair_bt)) },
            text = {
                LazyColumn {
                    item { Text(stringResource(R.string.pair_bt_hint)) }
                    items(list, key = { it.address }) { d ->
                        ListItem(
                            headlineContent = { Text(d.name ?: d.address) },
                            supportingContent = { Text(d.address) },
                            modifier =
                                Modifier.clickable {
                                    btList = null
                                    busy = true
                                    Toast.makeText(context, R.string.pair_bt_confirm, Toast.LENGTH_LONG).show()
                                    scope.launch { paired(runCatching { client.pair(d) }) }
                                },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { btList = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun UnitScreen(
    unit: HeadUnit,
    openPage: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    val state by client.state.collectAsStateWithLifecycle()
    val values by client.values.collectAsStateWithLifecycle()
    var pages by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    LaunchedEffect(unit.fingerprint) {
        if (client.connect(unit)) {
            pages =
                runCatching {
                    (client.call("pages") as JsonArray).map { p ->
                        val o = p as JsonObject
                        Triple(o.s("id"), o.s("title"), o.s("icon"))
                    }
                }.getOrDefault(emptyList())
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                when (val s = state) {
                    is LinkState.Connected -> stringResource(R.string.connected_via, s.via)
                    is LinkState.Connecting -> stringResource(R.string.connecting, s.name)
                    is LinkState.Failed -> stringResource(R.string.link_failed, s.message)
                    LinkState.Idle -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { Dashboard(values) }
        item {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                fun key(a: String) = scope.launch { runCatching { client.call("key", buildJsonObject { put("action", a) }) } }
                KeyButton(Icons.Default.SkipPrevious) { key("PREVIOUS") }
                KeyButton(Icons.Default.PlayArrow) { key("PLAY_PAUSE") }
                KeyButton(Icons.Default.SkipNext) { key("NEXT") }
                KeyButton(Icons.AutoMirrored.Filled.VolumeDown) { key("VOLUME_DOWN") }
                KeyButton(Icons.AutoMirrored.Filled.VolumeUp) { key("VOLUME_UP") }
                KeyButton(Icons.Default.VolumeOff) { key("MUTE") }
            }
        }
        items(pages, key = { it.first }) { (id, title, _) ->
            Card(Modifier.fillMaxWidth().clickable { openPage(id, title) }) { ListItem(headlineContent = { Text(title) }) }
        }
    }
}

@Composable
private fun KeyButton(
    icon: ImageVector,
    onClick: () -> Unit,
) = FilledTonalButton(onClick = onClick) { Icon(icon, null) }

/** Car battery, OBD values and the car's own values, as the head unit last sent them. */
@Composable
fun Dashboard(values: JsonObject?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.dashboard), style = MaterialTheme.typography.titleMedium)
            if (values == null) {
                Text(stringResource(R.string.no_values))
                return@Column
            }
            (values["battery"] as? JsonObject)?.let { b ->
                Text(stringResource(R.string.battery_line, Labels.battery(LocalContext.current, b)))
            }
            (values["obd"] as? JsonObject)?.forEach { (k, v) ->
                Text(Labels.obd(LocalContext.current, k) + " : " + Labels.format(k, (v as? JsonPrimitive)?.contentOrNull))
            }
            (values["car"] as? JsonObject)?.forEach { (k, v) -> Text(k.substringAfter('/') + " : " + ((v as? JsonPrimitive)?.contentOrNull ?: "—")) }
        }
    }
}

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
