package org.librehu.bridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.Remote.bool
import org.librehu.bridge.data.Remote.long
import org.librehu.bridge.data.Remote.str
import org.librehu.bridge.data.UnitInfo
import java.util.Locale

/** Colours of the GPS states, as on LibreHU Launcher's status bar (searching yellow, fix green). */
private val GPS_SEARCHING = Color(0xFFFDD663)
private val GPS_FIX = Color(0xFF81C995)

/**
 * The head unit at a glance, live (the `values` event, every second): car battery, GPS (fix, satellites used / in
 * view, their signals), temperatures of the SoC and the sensors with the CPU, memory and storage, OBD values, car
 * values, and the media keys.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Dashboard(
    info: UnitInfo?,
    connected: Boolean,
) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    val values by client.values.collectAsStateWithLifecycle()
    // GPS listened to on the head unit only while this screen is shown.
    if (info?.has("gps") == true) {
        DisposableEffect(connected) {
            if (connected) gpsWatch(scope, client, true)
            onDispose { if (connected) gpsWatch(CoroutineScope(Dispatchers.IO), client, false) }
        }
    }

    fun key(a: String) {
        scope.launch { runCatching { client.call("key", buildJsonObject { put("action", a) }) } }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { BatteryCard(values?.get("battery") as? JsonObject) }
        (values?.get("gps") as? JsonObject)?.let { g -> item { GpsCard(g) } }
        (values?.get("system") as? JsonObject)?.let { s -> item { SystemCard(s) } }
        val obd = (values?.get("obd") as? JsonObject).orEmpty().filterKeys { it != "BATTERY" }
        if (obd.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.live_values)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
                    obd.forEach { (k, v) ->
                        ValueTile(Labels.obd(context, k), Labels.format(k, (v as? JsonPrimitive)?.contentOrNull), iconFor(k), Modifier.weight(1f))
                    }
                }
            }
        }
        val car = (values?.get("car") as? JsonObject).orEmpty()
        if (car.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.car_values)) }
            item {
                Card(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        car.forEach { (k, v) ->
                            ListItem(
                                headlineContent = { Text(k.substringAfter('/')) },
                                trailingContent = { Text((v as? JsonPrimitive)?.contentOrNull ?: "—", style = MaterialTheme.typography.titleMedium) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
        if (connected) {
            item { SectionTitle(stringResource(R.string.media)) }
            item { MediaCard(::key) }
        }
        if (values == null && connected) {
            item { Text(stringResource(R.string.no_values), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (info != null && info.bridge < 2) {
            item { StatusBanner(stringResource(R.string.update_service), null) }
        }
    }
}

private fun gpsWatch(
    scope: CoroutineScope,
    client: BridgeClient,
    on: Boolean,
) {
    scope.launch { runCatching { client.call("gps.watch", buildJsonObject { put("on", on) }) } }
}

/** GPS: the launcher's icon and colours, satellites used / in view on a ring, each constellation, each signal. */
@Composable
private fun GpsCard(g: JsonObject) {
    val c = MaterialTheme.colorScheme
    val visible = g.long("visible").toInt()
    val used = g.long("used").toInt()
    val fix = g.bool("fix")
    val on = g.bool("enabled")
    val (icon, tint, state) =
        when {
            !on -> Triple(Icons.Default.GpsOff, c.onSurfaceVariant, stringResource(R.string.gps_off))
            fix -> Triple(Icons.Default.GpsFixed, GPS_FIX, stringResource(R.string.gps_fix))
            else -> Triple(Icons.Default.GpsNotFixed, GPS_SEARCHING, stringResource(R.string.gps_searching))
        }
    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    val track = c.surfaceContainerHighest
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 12.dp.toPx()
                        val topLeft = Offset(stroke / 2, stroke / 2)
                        val arc = Size(size.width - stroke, size.height - stroke)
                        drawArc(track, 135f, 270f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                        if (visible > 0) drawArc(tint, 135f, 270f * used / visible, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$used/$visible", style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.gps_sats), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.gps), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(state, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    val details =
                        listOfNotNull(
                            g["accuracyM"]?.let { stringResource(R.string.gps_accuracy, g.long("accuracyM")) },
                            g["speedKmh"]?.let { stringResource(R.string.gps_speed, g.long("speedKmh")) },
                            g["altitudeM"]?.let { stringResource(R.string.gps_altitude, g.long("altitudeM")) },
                            g["firstFixMs"]?.let { stringResource(R.string.gps_ttff, g.long("firstFixMs") / 1000.0) },
                            if (used > 0) stringResource(R.string.gps_cn0, g.long("cn0")) else null,
                        )
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
            val constellations = (g["constellations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (constellations.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    constellations.forEach { k ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${k.str("name")} ${k.long("used")}/${k.long("visible")}") },
                            colors = AssistChipDefaults.assistChipColors(labelColor = if (k.long("used") > 0) GPS_FIX else c.onSurfaceVariant),
                        )
                    }
                }
            }
            val sats = (g["sats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (sats.isNotEmpty()) SignalBars(sats, tint)
        }
    }
}

/** One bar per satellite, its C/N0 (0..50 dB-Hz), coloured when it is used in the fix. */
@Composable
private fun SignalBars(
    sats: List<JsonObject>,
    used: Color,
) {
    val idle = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val n = sats.size
        val w = size.width / n
        sats.forEachIndexed { i, s ->
            val f = (s.long("cn0") / 50f).coerceIn(0.04f, 1f)
            val h = size.height * f
            drawRect(if (s.bool("used")) used else idle, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
        }
    }
}

/** Temperatures (the hottest first: the SoC), CPU, memory, storage, uptime. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemCard(s: JsonObject) {
    val c = MaterialTheme.colorScheme
    val temps =
        (s["temps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { t ->
            (t["c"] as? JsonPrimitive)?.doubleOrNull?.let { t.str("name") to it }
        }.sortedByDescending { it.second }
    fun colour(t: Double) =
        when {
            t >= 80 -> c.error
            t >= 60 -> GPS_SEARCHING
            else -> GPS_FIX
        }
    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Thermostat, null, tint = temps.firstOrNull()?.let { colour(it.second) } ?: c.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.system), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                temps.firstOrNull()?.let { (_, t) -> Text(String.format(Locale.getDefault(), "%.0f °C", t), style = MaterialTheme.typography.headlineSmall, color = colour(t)) }
            }
            if (temps.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    temps.forEach { (name, t) ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${Labels.thermal(name)} ${String.format(Locale.getDefault(), "%.0f", t)} °C", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = AssistChipDefaults.assistChipColors(labelColor = colour(t)),
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.system_no_temps), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            val mhz = (s["cpuMhz"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.filter { it > 0 }
            val load = (s["load"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(
                        if (mhz.isNotEmpty()) stringResource(R.string.system_cpu, mhz.size, mhz.max()) else null,
                        load.firstOrNull()?.let { stringResource(R.string.system_load, it) },
                        stringResource(R.string.system_uptime, s.long("uptimeS") / 3600, s.long("uptimeS") % 3600 / 60),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            Usage(stringResource(R.string.system_memory), s.long("memTotal") - s.long("memFree"), s.long("memTotal"))
            (s["data"] as? JsonObject)?.let { Usage(stringResource(R.string.system_data), it.long("total") - it.long("free"), it.long("total")) }
            (s["sdcard"] as? JsonObject)?.let { Usage(stringResource(R.string.system_sdcard), it.long("total") - it.long("free"), it.long("total")) }
        }
    }
}

@Composable
private fun Usage(
    label: String,
    used: Long,
    total: Long,
) {
    if (total <= 0) return
    val f = (used.toFloat() / total).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${Labels.bytes(used)} / ${Labels.bytes(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { f },
            modifier = Modifier.fillMaxWidth(),
            color = if (f > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            drawStopIndicator = {},
        )
    }
}
