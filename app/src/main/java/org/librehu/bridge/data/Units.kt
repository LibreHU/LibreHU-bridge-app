package org.librehu.bridge.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.bridge.protocol.BridgeCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A paired head unit: how to reach it and the key it gave this phone. */
data class HeadUnit(
    val fingerprint: String,
    val name: String,
    val deviceId: String,
    /** Access key (base64url), kept encrypted by the Android keystore on disk. */
    val credential: String,
    val ips: List<String>,
    val port: Int,
    val bluetooth: String?,
)

/** What a pairing QR code says (`librehu://pair?…`, see the service's docs/bridge.md). */
data class PairingCode(
    val fingerprint: String,
    val token: ByteArray,
    val name: String,
    val ips: List<String>,
    val port: Int,
    val bluetooth: String?,
) {
    companion object {
        fun parse(text: String): PairingCode? {
            val u = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
            if (u.scheme != "librehu" || u.host != "pair" || u.getQueryParameter("v") != "1") return null
            return PairingCode(
                fingerprint = u.getQueryParameter("fp") ?: return null,
                token = runCatching { BridgeCrypto.unb64(u.getQueryParameter("t")!!) }.getOrNull() ?: return null,
                name = u.getQueryParameter("n") ?: "LibreHU",
                ips = u.getQueryParameter("ip").orEmpty().split(',').filter { it.isNotBlank() },
                port = u.getQueryParameter("p")?.toIntOrNull() ?: 47800,
                bluetooth = u.getQueryParameter("bt")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Paired head units, in private storage; access keys encrypted with a key of the Android keystore. */
class Units private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("units", Context.MODE_PRIVATE)
    private val _units = MutableStateFlow(load())
    val units: StateFlow<List<HeadUnit>> = _units.asStateFlow()

    fun save(u: HeadUnit) = store(_units.value.filter { it.fingerprint != u.fingerprint } + u)

    fun remove(fingerprint: String) = store(_units.value.filter { it.fingerprint != fingerprint })

    /** New addresses seen (the head unit's IP changes with the network). */
    fun updateIps(
        fingerprint: String,
        ips: List<String>,
    ) {
        val u = _units.value.firstOrNull { it.fingerprint == fingerprint } ?: return
        if (u.ips != ips) save(u.copy(ips = ips))
    }

    private fun store(list: List<HeadUnit>) {
        val a =
            JSONArray(
                list.map {
                    JSONObject()
                        .put("fp", it.fingerprint)
                        .put("name", it.name)
                        .put("id", it.deviceId)
                        .put("cred", seal(it.credential))
                        .put("ips", JSONArray(it.ips))
                        .put("port", it.port)
                        .put("bt", it.bluetooth ?: "")
                },
            )
        prefs.edit().putString("units", a.toString()).apply()
        _units.value = list
    }

    private fun load(): List<HeadUnit> =
        runCatching {
            val a = JSONArray(prefs.getString("units", "[]"))
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val cred = runCatching { open(o.getString("cred")) }.getOrNull() ?: return@mapNotNull null
                HeadUnit(
                    o.getString("fp"),
                    o.getString("name"),
                    o.getString("id"),
                    cred,
                    o.getJSONArray("ips").let { ips -> (0 until ips.length()).map { ips.getString(it) } },
                    o.optInt("port", 47800),
                    o.optString("bt").ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())

    // --- Android keystore -----------------------------------------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return BridgeCrypto.b64(c.iv) + "." + BridgeCrypto.b64(c.doFinal(plain.toByteArray()))
    }

    private fun open(sealed: String): String {
        val (iv, data) = sealed.split('.').let { BridgeCrypto.unb64(it[0]) to BridgeCrypto.unb64(it[1]) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(data))
    }

    companion object {
        private const val ALIAS = "librehu-bridge-units"

        @Volatile
        private var instance: Units? = null

        fun get(context: Context): Units = instance ?: synchronized(this) { instance ?: Units(context).also { instance = it } }
    }
}
