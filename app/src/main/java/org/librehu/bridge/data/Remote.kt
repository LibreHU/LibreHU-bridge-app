package org.librehu.bridge.data

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream

/** What the head unit's service offers (`hello`): bridge level 2 brings apps, reboots, system, GPS, files, logs, Android settings. */
data class UnitInfo(
    val name: String,
    val api: Int,
    val bridge: Int,
    val features: Set<String>,
    val root: Boolean,
    val rootFiles: Boolean,
) {
    fun has(feature: String) = feature in features
}

/** One entry of a folder of the head unit. */
data class RemoteFile(
    val name: String,
    val dir: Boolean,
    val size: Long,
    val mtime: Long,
)

/** Typed requests of the bridge (LibreHU-service docs/bridge.md). */
object Remote {
    /** Piece of a file per request: the service's largest (512 KiB), a multiple of 4096 as root reads need. */
    private const val CHUNK = 512 * 1024

    suspend fun hello(c: BridgeClient): UnitInfo {
        val o = c.call("hello") as JsonObject
        return UnitInfo(
            o.str("unit"),
            o.long("api").toInt(),
            o.long("bridge").toInt().coerceAtLeast(1),
            (o["features"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
            o.bool("root"),
            o.bool("rootFiles"),
        )
    }

    suspend fun list(
        c: BridgeClient,
        path: String,
    ): List<RemoteFile> =
        (c.call("fs.list", buildJsonObject { put("path", path) }, 30_000) as JsonArray).map {
            val o = it as JsonObject
            RemoteFile(o.str("name"), o.bool("dir"), o.long("size"), o.long("mtime"))
        }

    /** Copies the head unit's [path] into [out], [progress] with the bytes done and the size. */
    suspend fun download(
        c: BridgeClient,
        path: String,
        out: OutputStream,
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit,
    ) {
        var offset = 0L
        while (!cancelled()) {
            val r =
                c.call(
                    "fs.read",
                    buildJsonObject {
                        put("path", path)
                        put("offset", offset)
                        put("length", CHUNK)
                    },
                    60_000,
                ) as JsonObject
            val data = Base64.decode(r.str("data"), Base64.DEFAULT)
            out.write(data)
            offset += data.size
            val size = r.long("size")
            progress(offset, size)
            if (r.bool("eof") || data.isEmpty()) break
        }
    }

    /** Writes [input] ([size] bytes, -1 unknown) to the head unit's [path], in pieces. */
    suspend fun upload(
        c: BridgeClient,
        input: InputStream,
        size: Long,
        path: String,
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit,
    ) {
        val buf = ByteArray(CHUNK / 2)
        var offset = 0L
        while (!cancelled()) {
            var n = 0
            while (n < buf.size) {
                val k = input.read(buf, n, buf.size - n)
                if (k < 0) break
                n += k
            }
            if (n == 0 && offset > 0) break
            c.call(
                "fs.write",
                buildJsonObject {
                    put("path", path)
                    put("offset", offset)
                    put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                },
                60_000,
            )
            offset += n
            progress(offset, size)
            if (n < buf.size) break
        }
    }

    suspend fun simple(
        c: BridgeClient,
        method: String,
        vararg params: Pair<String, String>,
        timeoutMs: Long = 30_000,
    ) = c.call(method, buildJsonObject { params.forEach { (k, v) -> put(k, v) } }, timeoutMs)

    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
}
