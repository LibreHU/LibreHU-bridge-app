package org.librehu.bridge.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.LinkState
import org.librehu.bridge.data.Units
import org.librehu.bridge.ui.Labels

/**
 * The LibreHU app in Android Auto (Car App Library, IoT category): the car battery and OBD values of the paired head
 * unit, and its on / off settings as quick toggles. Only Android Auto hosts signed by Google are accepted
 * (the library's allow list). Sideloaded apps show in Android Auto once "Unknown sources" is on in its developer
 * settings.
 */
class BridgeCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator
            .Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session =
        object : Session() {
            override fun onCreateScreen(intent: Intent): Screen = ValuesScreen(carContext)
        }
}

private class ValuesScreen(
    context: CarContext,
) : Screen(context) {
    private val client = BridgeClient.get(context)

    init {
        lifecycleScope.launch {
            val unit = Units.get(carContext).units.value.firstOrNull()
            if (unit != null) client.connect(unit)
            // Templates are limited in how often they change: twice a second at most is plenty.
            while (true) {
                invalidate()
                delay(2_000)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val state = client.state.value
        val values = client.values.value
        when {
            Units.get(carContext).units.value.isEmpty() -> list.setNoItemsMessage(carContext.getString(R.string.car_no_unit))
            state !is LinkState.Connected -> list.setNoItemsMessage(carContext.getString(R.string.car_not_connected))
            else -> {
                (values?.get("battery") as? JsonObject)?.let {
                    list.addItem(Row.Builder().setTitle(carContext.getString(R.string.obd_battery)).addText(Labels.battery(carContext, it)).build())
                }
                val obd = values?.get("obd") as? JsonObject
                for (k in listOf("SPEED", "RPM", "COOLANT_TEMP", "FUEL_LEVEL", "OIL_TEMP")) {
                    val v = (obd?.get(k) as? JsonPrimitive)?.contentOrNull ?: continue
                    list.addItem(Row.Builder().setTitle(Labels.obd(carContext, k)).addText(Labels.format(k, v)).build())
                }
                list.addItem(
                    Row
                        .Builder()
                        .setTitle(carContext.getString(R.string.car_quick))
                        .setBrowsable(true)
                        .setOnClickListener { screenManager.push(QuickScreen(carContext)) }
                        .build(),
                )
            }
        }
        return ListTemplate
            .Builder()
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}

/** On / off settings of every page, as a grid: a touch switches it. */
private class QuickScreen(
    context: CarContext,
) : Screen(context) {
    private val client = BridgeClient.get(context)
    private var toggles: List<Triple<String, JsonObject, Boolean>>? = null

    init {
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            toggles =
                runCatching {
                    (client.call("pages") as JsonArray).flatMap { p ->
                        val id = ((p as JsonObject)["id"] as JsonPrimitive).content
                        val page = client.call("page", buildJsonObject { put("id", id) }) as JsonObject
                        (page["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.type() == "switch" }.map {
                            Triple(id, it, (it["value"] as? JsonPrimitive)?.booleanOrNull == true)
                        }
                    }
                }.getOrDefault(emptyList()).take(MAX)
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val t = toggles
        if (t != null) {
            if (t.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_not_connected))
            for ((page, item, on) in t) {
                list.addItem(
                    GridItem
                        .Builder()
                        .setTitle(item.label())
                        .setText(carContext.getString(if (on) R.string.car_on else R.string.car_off))
                        .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, if (on) R.drawable.ic_toggle_on else R.drawable.ic_toggle_off)).build())
                        .setOnClickListener {
                            lifecycleScope.launch {
                                runCatching {
                                    client.call(
                                        "set",
                                        buildJsonObject {
                                            put("page", page)
                                            put("item", (item["id"] as JsonPrimitive).content)
                                            put("value", !on)
                                        },
                                    )
                                }
                                load()
                            }
                        }.build(),
                )
            }
        }
        val b =
            GridTemplate
                .Builder()
                .setTitle(carContext.getString(R.string.car_quick))
                .setHeaderAction(Action.BACK)
        if (t == null) b.setLoading(true) else b.setSingleList(list.build())
        return b.build()
    }

    private fun JsonObject.type() = (this["type"] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.label() = (this["label"] as? JsonPrimitive)?.contentOrNull ?: ""

    companion object {
        private const val MAX = 12
    }
}
