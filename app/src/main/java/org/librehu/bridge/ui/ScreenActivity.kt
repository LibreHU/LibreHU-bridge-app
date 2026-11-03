package org.librehu.bridge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.Remote.long
import org.librehu.bridge.screen.ScreenDecoder

/**
 * The head unit's screen on the phone, touched from here: for the passenger. LibreHU-service shares it over Wi-Fi
 * (root on the head unit): H.264 decoded into a SurfaceView, the fingers and the Back / Home / recent apps keys sent
 * back. The share runs while this screen is shown; the head unit's screen stays as it is for the driver.
 */
class ScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { BridgeTheme { ScreenView(onClose = ::finish) } }
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, ScreenActivity::class.java))
        }
    }
}

/**
 * Frames of one share: kept until the video's surface and size are known (the first frames, SPS / PPS and key frame,
 * may arrive before the answer to `screen.start`), then decoded.
 */
private class ScreenSession(
    private val client: BridgeClient,
    private val onStopped: (String) -> Unit,
) : BridgeClient.ScreenSink {
    private val early = ArrayList<ByteArray>()
    private var decoder: ScreenDecoder? = null

    override fun frame(data: ByteArray) {
        synchronized(this) {
            decoder?.let {
                it.feed(data)
                return
            }
            if (early.size < EARLY_MAX) early.add(data)
        }
    }

    override fun event(d: JsonObject) {
        if ((d["on"] as? JsonPrimitive)?.contentOrNull == "false") onStopped((d["e"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    fun attach(
        holder: SurfaceHolder,
        size: IntSize,
    ) {
        synchronized(this) {
            if (decoder != null) return
            val dec = ScreenDecoder(holder.surface, size.width, size.height) { client.post("screen.sync", JsonObject(emptyMap())) }
            early.forEach(dec::feed)
            early.clear()
            decoder = dec
        }
    }

    fun detach() {
        synchronized(this) {
            decoder?.close()
            decoder = null
            early.clear()
        }
    }

    companion object {
        private const val EARLY_MAX = 120
    }
}

@Composable
private fun ScreenView(onClose: () -> Unit) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<IntSize?>(null) }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    val session =
        remember(attempt) {
            ScreenSession(client) { why ->
                scope.launch {
                    size = null
                    error = why.ifBlank { context.getString(R.string.screen_ended) }
                }
            }
        }
    // Shared while shown: started at each start of the screen, stopped when it leaves (or is hidden).
    LifecycleStartEffect(session) {
        error = ""
        size = null
        client.screenSink = session
        val job =
            scope.launch {
                runCatching { client.call("screen.start", buildJsonObject { put("max", MAX_SIDE) }, timeoutMs = 30_000) as JsonObject }
                    .onSuccess { o -> size = IntSize(o.long("w").toInt(), o.long("h").toInt()).takeIf { it.width > 0 && it.height > 0 } }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        onStopOrDispose {
            job.cancel()
            if (client.screenSink === session) client.screenSink = null
            client.post("screen.stop", JsonObject(emptyMap()))
            session.detach()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        val landscape = maxWidth > maxHeight
        val video: @Composable (Modifier) -> Unit = { m ->
            Box(m, contentAlignment = Alignment.Center) {
                val s = size
                when {
                    error.isNotEmpty() ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                            Text(error, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { attempt++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry), tint = Color.White) }
                        }
                    s == null -> CircularProgressIndicator()
                    else -> Video(s, session, client)
                }
            }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxHeight())
                Keys(vertical = true, client = client, onClose = onClose)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxWidth())
                Keys(vertical = false, client = client, onClose = onClose)
            }
        }
    }
}

/** The video, as large as the room allows at the head unit's proportions; each finger goes to the head unit. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Video(
    size: IntSize,
    session: ScreenSession,
    client: BridgeClient,
) {
    DisposableEffect(session) { onDispose { session.detach() } }
    AndroidView(
        factory = { c ->
            SurfaceView(c).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = session.attach(holder, size)

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) = session.detach()
                    },
                )
                setOnTouchListener { v, e ->
                    touch(client, e, v.width.toFloat(), v.height.toFloat())
                    true
                }
            }
        },
        modifier = Modifier.aspectRatio(size.width.toFloat() / size.height),
    )
}

/** Android's touches to `screen.touch`: `a` (d, m, u, c) and `f` (id, x, y of each finger, from 0 to 1). */
private fun touch(
    client: BridgeClient,
    e: MotionEvent,
    w: Float,
    h: Float,
) {
    if (w <= 0 || h <= 0) return
    fun finger(i: Int) = listOf(e.getPointerId(i).toFloat(), (e.getX(i) / w).coerceIn(0f, 1f), (e.getY(i) / h).coerceIn(0f, 1f))
    val (a, f) =
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> "d" to finger(e.actionIndex)
            MotionEvent.ACTION_MOVE -> "m" to (0 until e.pointerCount).flatMap { finger(it) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "u" to finger(e.actionIndex)
            MotionEvent.ACTION_CANCEL -> "c" to emptyList()
            else -> return
        }
    client.post(
        "screen.touch",
        buildJsonObject {
            put("a", a)
            put("f", JsonArray(f.map { JsonPrimitive(it) }))
        },
    )
}

/** Back, Home, recent apps of the head unit, and closing this screen. */
@Composable
private fun Keys(
    vertical: Boolean,
    client: BridgeClient,
    onClose: () -> Unit,
) {
    val keys: List<Triple<ImageVector, Int, () -> Unit>> =
        listOf(
            Triple(Icons.AutoMirrored.Filled.ArrowBack, R.string.screen_back) { key(client, 4) },
            Triple(Icons.Default.Home, R.string.screen_home) { key(client, 3) },
            Triple(Icons.Default.CropSquare, R.string.screen_recents) { key(client, 187) },
            Triple(Icons.Default.Close, R.string.screen_close, onClose),
        )
    val content: @Composable () -> Unit = {
        keys.forEach { (icon, label, action) ->
            IconButton(onClick = action, modifier = Modifier.size(56.dp)) { Icon(icon, stringResource(label), tint = Color.White) }
        }
    }
    if (vertical) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 4.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

private fun key(
    client: BridgeClient,
    code: Int,
) = client.post("screen.key", buildJsonObject { put("code", code) })

/** Largest side of the video asked for: the head unit's own screen (1280 × 720 or 1024 × 600). */
private const val MAX_SIDE = 1280
