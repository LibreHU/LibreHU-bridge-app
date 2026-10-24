package org.librehu.bridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.AutoVisibility
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.HeadUnit
import org.librehu.bridge.data.LinkState
import org.librehu.bridge.data.PairingCode
import org.librehu.bridge.data.Units
import java.util.Locale

/** Where the app is: the paired head units, one head unit, or one of its pages ([depth] for the slide direction). */
private sealed class Nav(
    val depth: Int,
) {
    data object Home : Nav(0)

    data class Unit(
        val unit: HeadUnit,
    ) : Nav(1)

    data class Page(
        val unit: HeadUnit,
        val id: String,
        val title: String,
    ) : Nav(2)
}

/** LibreHU's phone app: pairs with head units (QR code or Bluetooth) and shows what their service describes. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { BridgeTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    var nav by remember { mutableStateOf<Nav>(Nav.Home) }

    fun back() {
        nav = (nav as? Nav.Page)?.let { Nav.Unit(it.unit) } ?: Nav.Home
    }
    BackHandler(nav != Nav.Home) { back() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        when (val n = nav) {
                            Nav.Home -> stringResource(R.string.app_name)
                            is Nav.Unit -> n.unit.name
                            is Nav.Page -> n.title
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (nav != Nav.Home) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
    ) { pad ->
        AnimatedContent(
            targetState = nav,
            modifier = Modifier.padding(pad).fillMaxSize(),
            transitionSpec = {
                val forward = targetState.depth >= initialState.depth
                (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
            },
            label = "nav",
        ) { n ->
            when (n) {
                Nav.Home -> HomeScreen { nav = Nav.Unit(it) }
                is Nav.Unit -> UnitScreen(n.unit) { id, title -> nav = Nav.Page(n.unit, id, title) }
                is Nav.Page -> PageScreen(n.id)
            }
        }
    }
}

// --- Home ------------------------------------------------------------------------------------------------------

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
    var forget by remember { mutableStateOf<HeadUnit?>(null) }

    fun paired(result: Result<HeadUnit>) {
        busy = false
        result
            .onSuccess { open(it) }
            .onFailure { Toast.makeText(context, context.getString(R.string.pair_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
    }
    val scan =
        rememberLauncherForActivityResult(ScanContract()) { r ->
            val code = r.contents?.let { PairingCode.parse(it) }
            if (r.contents != null && code == null) Toast.makeText(context, R.string.qr_not_librehu, Toast.LENGTH_LONG).show()
            if (code != null) {
                busy = true
                scope.launch { paired(runCatching { client.pair(code) }) }
            }
        }
    val btPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) btList = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        }
    val scanQr = {
        scan.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(context.getString(R.string.scan_prompt)).setBeepEnabled(false),
        )
    }
    val pickBt = {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            btList = BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (units.isEmpty()) {
            item { Welcome() }
        } else {
            item { SectionTitle(stringResource(R.string.your_units)) }
            items(units, key = { it.fingerprint }) { u -> UnitCard(u, onOpen = { open(u) }, onForget = { forget = u }) }
        }
        (state as? LinkState.Connecting)?.let { item { StatusBanner(stringResource(R.string.connecting, it.name), null, busy = true) } }
        (state as? LinkState.Failed)?.let { item { StatusBanner(stringResource(R.string.link_failed, it.message), false) } }
        item { SectionTitle(stringResource(if (units.isEmpty()) R.string.pair_title else R.string.pair_another)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PairCard(Icons.Default.QrCodeScanner, stringResource(R.string.pair_qr), stringResource(R.string.pair_qr_sub), !busy, Modifier.weight(1f), scanQr)
                PairCard(Icons.Default.Bluetooth, stringResource(R.string.pair_bt), stringResource(R.string.pair_bt_sub), !busy, Modifier.weight(1f), pickBt)
            }
        }
        item { AndroidAutoCard() }
    }
    btList?.let { list ->
        AlertDialog(
            onDismissRequest = { btList = null },
            icon = { Icon(Icons.Default.Bluetooth, null) },
            title = { Text(stringResource(R.string.pair_bt)) },
            text = {
                LazyColumn {
                    item { Text(stringResource(R.string.pair_bt_hint), style = MaterialTheme.typography.bodyMedium) }
                    items(list, key = { it.address }) { d ->
                        ListItem(
                            headlineContent = { Text(d.name ?: d.address) },
                            supportingContent = { Text(d.address, fontFamily = FontFamily.Monospace) },
                            leadingContent = { Icon(Icons.Default.DirectionsCar, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier =
                                Modifier.clip(MaterialTheme.shapes.small).clickable {
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
    forget?.let { u ->
        AlertDialog(
            onDismissRequest = { forget = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(stringResource(R.string.forget_title, u.name)) },
            text = { Text(stringResource(R.string.forget_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Units.get(context).remove(u.fingerprint)
                    forget = null
                }) { Text(stringResource(R.string.forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** First start: what the app does and the three steps. */
@Composable
private fun Welcome() {
    val c = MaterialTheme.colorScheme
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(c.primaryContainer, c.surfaceContainerHigh))).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DirectionsCar, null, tint = c.onPrimary, modifier = Modifier.size(36.dp))
            }
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, color = c.onPrimaryContainer)
            Text(stringResource(R.string.home_hint), style = MaterialTheme.typography.bodyLarge, color = c.onPrimaryContainer)
            Step(1, stringResource(R.string.step_1))
            Step(2, stringResource(R.string.step_2))
            Step(3, stringResource(R.string.step_3))
        }
    }
}

@Composable
private fun Step(
    n: Int,
    text: String,
) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = c.primary)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onPrimaryContainer)
    }
}

@Composable
internal fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

@Composable
private fun PairCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = onClick, enabled = enabled) { Icon(icon, null) }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnitCard(
    u: HeadUnit,
    onOpen: () -> Unit,
    onForget: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    ElevatedCard(onClick = onOpen, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DirectionsCar, null, tint = c.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(u.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.unit_fp, u.fingerprint.take(8)), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.onSurfaceVariant)
            }
            IconButton(onClick = onForget) { Icon(Icons.Default.Delete, stringResource(R.string.forget), tint = c.onSurfaceVariant) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StatusBanner(
    text: String,
    ok: Boolean?,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val bg =
        when (ok) {
            true -> c.tertiary.copy(alpha = 0.15f)
            false -> c.error.copy(alpha = 0.12f)
            null -> c.surfaceContainerHigh
        }
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            ok == true -> Icon(Icons.Outlined.CheckCircle, null, tint = c.tertiary)
            ok == false -> Icon(Icons.Outlined.ErrorOutline, null, tint = c.error)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * Android Auto lists a Car App Library app only when a trusted store installed it ([AutoVisibility]): shown with the
 * ways to get there (root on the phone, or adb from a computer).
 */
@Composable
private fun AndroidAutoCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installer by remember { mutableStateOf(AutoVisibility.installer(context)) }
    var working by remember { mutableStateOf(false) }
    val visible = installer == AutoVisibility.PLAY
    val c = MaterialTheme.colorScheme
    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (visible) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (visible) c.tertiary else c.error)
                Text(stringResource(R.string.aa_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                if (visible) stringResource(R.string.aa_ok) else stringResource(R.string.aa_hidden, installer ?: stringResource(R.string.aa_no_installer)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!visible) {
                Button(enabled = !working, onClick = {
                    working = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { AutoVisibility.reinstallAsPlay(context) }
                        working = false
                        r.onFailure { Toast.makeText(context, context.getString(R.string.aa_root_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
                        installer = AutoVisibility.installer(context)
                    }
                }) { Text(stringResource(R.string.aa_fix_root)) }
                Text(stringResource(R.string.aa_adb), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(c.surfaceContainerHighest).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        AutoVisibility.ADB_COMMAND,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", AutoVisibility.ADB_COMMAND))
                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }
                }
                Text(stringResource(R.string.aa_then), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

// --- Shared pieces of the head unit's screens ------------------------------------------------------------------

/** Car battery: an arc of its charge (engine off) or of its voltage, and what it is doing. */
@Composable
internal fun BatteryCard(b: JsonObject?) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val volts = (b?.get("voltage") as? JsonPrimitive)?.doubleOrNull
    val percent = (b?.get("percent") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
    val status = (b?.get("status") as? JsonPrimitive)?.contentOrNull
    val fraction = percent?.div(100f) ?: volts?.let { ((it - 11.5) / (15.0 - 11.5)).toFloat().coerceIn(0f, 1f) } ?: 0f
    val colour =
        when {
            status == "OVERVOLTAGE" || status == "NOT_CHARGING" -> c.error
            percent != null && percent < 30 -> c.error
            status == "CHARGING" -> c.tertiary
            else -> c.primary
        }
    val track = c.surfaceContainerHighest
    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val topLeft = Offset(stroke / 2, stroke / 2)
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (volts != null) drawArc(colour, 135f, 270f * fraction, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        percent?.let { "$it %" } ?: volts?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (percent == null && volts != null) Text("V", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
            Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.battery), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (b == null || volts == null) stringResource(R.string.battery_none) else Labels.battery(context, b),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ValueTile(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier,
) {
    Card(shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun MediaCard(key: (String) -> Unit) {
    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { key("PREVIOUS") }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, null) }
                FilledIconButton(onClick = { key("PLAY_PAUSE") }, modifier = Modifier.size(72.dp)) { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(36.dp)) }
                FilledTonalIconButton(onClick = { key("NEXT") }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, null) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { key("VOLUME_DOWN") }) { Icon(Icons.AutoMirrored.Filled.VolumeDown, null) }
                OutlinedButton(onClick = { key("MUTE") }) { Icon(Icons.AutoMirrored.Filled.VolumeOff, null) }
                OutlinedButton(onClick = { key("VOLUME_UP") }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) }
            }
        }
    }
}

@Composable
internal fun PageTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Icon of a page from the name the head unit gives. */
internal fun pageIcon(name: String): ImageVector =
    when (name) {
        "settings" -> Icons.Default.Settings
        "volume" -> Icons.AutoMirrored.Filled.VolumeUp
        "light" -> Icons.Default.Lightbulb
        "car" -> Icons.Default.DirectionsCar
        "weather" -> Icons.Default.WbSunny
        "phone" -> Icons.Outlined.Phone
        else -> Icons.Default.Tune
    }

/** Icon of an OBD value. */
internal fun iconFor(key: String): ImageVector =
    when {
        key == "SPEED" || key == "RPM" -> Icons.Default.Speed
        key.endsWith("_TEMP") -> Icons.Default.Thermostat
        key.startsWith("FUEL") -> Icons.Outlined.LocalGasStation
        else -> Icons.Default.Tune
    }

internal fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
