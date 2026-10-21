package org.librehu.bridge.ui

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.librehu.bridge.R
import java.util.Locale

/** Names and units of the values the head unit sends (LibreHU-service's OBD names). */
object Labels {
    private val OBD =
        mapOf(
            "SPEED" to R.string.obd_speed,
            "RPM" to R.string.obd_rpm,
            "COOLANT_TEMP" to R.string.obd_coolant,
            "OIL_TEMP" to R.string.obd_oil,
            "INTAKE_TEMP" to R.string.obd_intake,
            "AMBIENT_TEMP" to R.string.obd_ambient,
            "ENGINE_LOAD" to R.string.obd_load,
            "THROTTLE" to R.string.obd_throttle,
            "FUEL_LEVEL" to R.string.obd_fuel,
            "FUEL_RATE" to R.string.obd_fuel_rate,
            "BATTERY" to R.string.obd_battery,
        )
    private val UNITS =
        mapOf(
            "SPEED" to "km/h", "RPM" to "tr/min", "COOLANT_TEMP" to "°C", "OIL_TEMP" to "°C", "INTAKE_TEMP" to "°C",
            "AMBIENT_TEMP" to "°C", "ENGINE_LOAD" to "%", "THROTTLE" to "%", "FUEL_LEVEL" to "%", "FUEL_RATE" to "L/h",
            "BATTERY" to "V", "MODULE_VOLTAGE" to "V", "MAF" to "g/s",
        )

    fun obd(
        context: Context,
        key: String,
    ): String = OBD[key]?.let { context.getString(it) } ?: key.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun format(
        key: String,
        value: String?,
    ): String {
        val v = value?.toDoubleOrNull() ?: return "—"
        val decimals = if (key == "BATTERY" || key == "MODULE_VOLTAGE" || key == "FUEL_RATE") 1 else 0
        return String.format(Locale.getDefault(), "%.${decimals}f", v) + (UNITS[key]?.let { " $it" } ?: "")
    }

    fun battery(
        context: Context,
        b: JsonObject,
    ): String {
        val v = (b["voltage"] as? JsonPrimitive)?.doubleOrNull ?: return "—"
        val p = (b["percent"] as? JsonPrimitive)?.intOrNull
        val status =
            when ((b["status"] as? JsonPrimitive)?.contentOrNull) {
                "CHARGING" -> context.getString(R.string.bat_charging)
                "NOT_CHARGING" -> context.getString(R.string.bat_not_charging)
                "OVERVOLTAGE" -> context.getString(R.string.bat_over)
                else -> p?.let { "$it %" } ?: ""
            }
        return String.format(Locale.getDefault(), "%.1f V", v) + if (status.isNotEmpty()) " · $status" else ""
    }
}
