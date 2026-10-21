package org.librehu.bridge.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.protocol.BridgeChannel
import org.librehu.bridge.protocol.BridgeCrypto
import org.librehu.bridge.protocol.BridgeException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class LinkState {
    data object Idle : LinkState()

    data class Connecting(
        val name: String,
    ) : LinkState()

    data class Connected(
        val unit: HeadUnit,
        val via: String,
    ) : LinkState()

    data class Failed(
        val message: String,
    ) : LinkState()
}

/**
 * The link to one head unit (LibreHU-service's bridge): Wi-Fi first (each address known, then the port), Bluetooth
 * next; the encrypted [BridgeChannel], the phone's authentication, then requests and the `values` events.
 */
class BridgeClient private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val units = Units.get(app)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Last `values` event (OBD, battery, car values…). */
    private val _values = MutableStateFlow<JsonObject?>(null)
    val values: StateFlow<JsonObject?> = _values.asStateFlow()

    private val lock = Mutex()
    private var channel: BridgeChannel? = null
    private var transport: Closeable? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val ids = AtomicInteger(1)

    private class Link(
        val input: InputStream,
        val output: OutputStream,
        val close: Closeable,
        val via: String,
    )

    /** Pairs with the head unit of a scanned QR code. */
    suspend fun pair(code: PairingCode): HeadUnit =
        withContext(Dispatchers.IO) {
            lock.withLock {
                close()
                _state.value = LinkState.Connecting(code.name)
                val link =
                    open(code.ips, code.port, code.bluetooth)
                        ?: throw BridgeException("head unit not reachable: same Wi-Fi network (hotspot) or Bluetooth on?")
                try {
                    val ch = BridgeChannel.connect(link.input, link.output, code.fingerprint)
                    ch.send(
                        buildJsonObject {
                            put("mode", "qr")
                            put("name", phoneName())
                            put("proof", BridgeCrypto.b64(BridgeCrypto.hmac(code.token, ch.transcript)))
                        },
                    )
                    finishPairing(ch, link, code.fingerprint, code.ips, code.port, code.bluetooth)
                } catch (e: Exception) {
                    link.close.close()
                    fail(e)
                }
            }
        }

    /** Pairs over Bluetooth with a head unit already paired with this phone: confirmed on the head unit. */
    @SuppressLint("MissingPermission")
    suspend fun pair(device: BluetoothDevice): HeadUnit =
        withContext(Dispatchers.IO) {
            lock.withLock {
                close()
                _state.value = LinkState.Connecting(device.name ?: device.address)
                val link = bluetooth(device.address) ?: throw BridgeException("Bluetooth connection failed (LibreHU-service with the phone app access on?)")
                try {
                    val ch = BridgeChannel.connect(link.input, link.output, null)
                    ch.send(buildJsonObject { put("mode", "bt"); put("name", phoneName()) })
                    finishPairing(ch, link, ch.serverFingerprint, emptyList(), 47800, device.address)
                } catch (e: Exception) {
                    link.close.close()
                    fail(e)
                }
            }
        }

    private fun finishPairing(
        ch: BridgeChannel,
        link: Link,
        fingerprint: String,
        ips: List<String>,
        port: Int,
        bt: String?,
    ): HeadUnit {
        val r = ch.receive()
        if ((r["ok"] as? JsonPrimitive)?.contentOrNull != "true") throw BridgeException(r.str("e") ?: "refused")
        val unit = HeadUnit(fingerprint, r.str("unit") ?: "LibreHU", r.str("id")!!, r.str("credential")!!, ips, port, bt)
        units.save(unit)
        started(ch, link, unit)
        return unit
    }

    /** Connects to a paired head unit (nothing when already connected to it). */
    suspend fun connect(unit: HeadUnit): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                (state.value as? LinkState.Connected)?.let { if (it.unit.fingerprint == unit.fingerprint && channel != null) return@withContext true }
                close()
                _state.value = LinkState.Connecting(unit.name)
                val link = open(unit.ips, unit.port, unit.bluetooth)
                if (link == null) {
                    _state.value = LinkState.Failed("not reachable")
                    return@withContext false
                }
                try {
                    val ch = BridgeChannel.connect(link.input, link.output, unit.fingerprint)
                    ch.send(
                        buildJsonObject {
                            put("mode", "cred")
                            put("id", unit.deviceId)
                            put("name", phoneName())
                            put("proof", BridgeCrypto.b64(BridgeCrypto.hmac(BridgeCrypto.unb64(unit.credential), ch.transcript)))
                        },
                    )
                    val r = ch.receive()
                    if ((r["ok"] as? JsonPrimitive)?.contentOrNull != "true") throw BridgeException(r.str("e") ?: "refused")
                    started(ch, link, unit.copy(name = r.str("unit") ?: unit.name))
                    true
                } catch (e: Exception) {
                    link.close.close()
                    _state.value = LinkState.Failed(e.message ?: e.javaClass.simpleName)
                    false
                }
            }
        }

    /** One request; throws [BridgeException] with the head unit's error. */
    suspend fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonElement {
        val ch = channel ?: throw BridgeException("not connected")
        val id = ids.getAndIncrement()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        return try {
            withContext(Dispatchers.IO) {
                ch.send(
                    buildJsonObject {
                        put("id", id)
                        put("m", method)
                        put("p", params)
                    },
                )
            }
            val r = withTimeout(CALL_TIMEOUT_MS) { answer.await() }
            r["e"]?.let { throw BridgeException((it as? JsonPrimitive)?.contentOrNull ?: "error") }
            r["r"] ?: JsonPrimitive(true)
        } finally {
            pending.remove(id)
        }
    }

    fun disconnect() {
        close()
        _state.value = LinkState.Idle
    }

    private fun started(
        ch: BridgeChannel,
        link: Link,
        unit: HeadUnit,
    ) {
        channel = ch
        transport = link.close
        _state.value = LinkState.Connected(unit, link.via)
        Thread({ readLoop(ch) }, "bridge-read").apply { isDaemon = true }.start()
        runCatching {
            ch.send(
                buildJsonObject {
                    put("id", 0)
                    put("m", "subscribe")
                },
            )
        }
    }

    private fun readLoop(ch: BridgeChannel) {
        try {
            while (channel === ch) {
                val m = ch.receive()
                if (m["ev"] != null) {
                    if (m.str("ev") == "values") _values.value = m["d"] as? JsonObject
                    continue
                }
                val id = (m["id"] as? JsonPrimitive)?.intOrNull ?: continue
                pending[id]?.complete(m)
            }
        } catch (e: Exception) {
            if (channel === ch) {
                Log.i(TAG, "link closed: ${e.message}")
                close()
                _state.value = LinkState.Failed(e.message ?: "closed")
            }
        }
    }

    private fun close() {
        channel = null
        runCatching { transport?.close() }
        transport = null
        pending.values.forEach { it.completeExceptionally(BridgeException("closed")) }
        pending.clear()
        _values.value = null
    }

    private fun fail(e: Exception): Nothing {
        _state.value = LinkState.Failed(e.message ?: e.javaClass.simpleName)
        throw e as? BridgeException ?: BridgeException(e.message ?: e.javaClass.simpleName)
    }

    private fun open(
        ips: List<String>,
        port: Int,
        bt: String?,
    ): Link? {
        for (ip in ips) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), TCP_TIMEOUT_MS)
                s.tcpNoDelay = true
                return Link(s.getInputStream(), s.getOutputStream(), s, "Wi-Fi $ip")
            } catch (_: Exception) {
            }
        }
        return bt?.let { bluetooth(it) }
    }

    @SuppressLint("MissingPermission")
    private fun bluetooth(address: String): Link? =
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!adapter.isEnabled) return null
            val s = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(UUID_RFCOMM)
            runCatching { adapter.cancelDiscovery() }
            s.connect()
            Link(s.inputStream, s.outputStream, s, "Bluetooth")
        } catch (e: Exception) {
            Log.i(TAG, "bluetooth: ${e.message}")
            null
        }

    private fun phoneName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").take(64)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TAG = "LibreHU-Bridge"
        private const val TCP_TIMEOUT_MS = 2_500
        private const val CALL_TIMEOUT_MS = 15_000L
        val UUID_RFCOMM: UUID = UUID.fromString("6c1b2e6a-8f0e-4b8c-9a51-1ebd2d4c0b01")

        @Volatile
        private var instance: BridgeClient? = null

        fun get(context: Context): BridgeClient = instance ?: synchronized(this) { instance ?: BridgeClient(context).also { instance = it } }
    }
}
