package org.librehu.bridge.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.librehu.bridge.R
import org.librehu.bridge.data.BridgeClient
import org.librehu.bridge.data.Remote
import org.librehu.bridge.data.Remote.str
import org.librehu.bridge.data.RemoteFile
import org.librehu.bridge.data.UnitInfo
import java.text.DateFormat
import java.util.Date

/** A transfer running: what, and how far (0..1, -1 unknown). */
private data class Transfer(
    val name: String,
    val upload: Boolean,
    val progress: Float,
)

/**
 * The head unit's files: its internal storage and USB keys / SD cards (the whole file system only when root files
 * are allowed on the head unit), folders first; a file is downloaded to the phone (Android's picker chooses where),
 * renamed or deleted; a phone file is sent into the folder shown; new folder. Transfers in 512 KiB pieces with their
 * progress, cancellable.
 */
@Composable
fun FilesTab(info: UnitInfo?) {
    val context = LocalContext.current
    val client = BridgeClient.get(context)
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var path by remember { mutableStateOf("/sdcard") }
    var entries by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var transfer by remember { mutableStateOf<Transfer?>(null) }
    var cancel by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<RemoteFile?>(null) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }

    LaunchedEffect(Unit) {
        roots =
            runCatching {
                (client.call("fs.roots") as JsonArray).mapNotNull { it as? JsonObject }.map { it.str("path") to it.str("kind") }
            }.getOrDefault(listOf("/sdcard" to "internal"))
    }
    LaunchedEffect(path, reload) {
        entries = null
        error = ""
        runCatching { Remote.list(client, path) }.onSuccess { entries = it }.onFailure { error = it.message ?: "" }
    }

    fun child(name: String) = if (path == "/") "/$name" else "$path/$name"

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            val f = pendingDownload ?: return@rememberLauncherForActivityResult
            pendingDownload = null
            if (uri == null) return@rememberLauncherForActivityResult
            cancel = false
            transfer = Transfer(f.name, false, 0f)
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)!!.use { out ->
                            Remote.download(client, child(f.name), out, { cancel }) { done, size ->
                                transfer = Transfer(f.name, false, if (size > 0) done.toFloat() / size else -1f)
                            }
                        }
                    }
                }.onSuccess { toast(context.getString(if (cancel) R.string.files_cancelled else R.string.files_downloaded, f.name)) }
                    .onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                transfer = null
            }
        }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val (name, size) =
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: "file") to c.getLong(1) else null
                } ?: ("file" to -1L)
            val safe = name.replace('/', '_')
            cancel = false
            transfer = Transfer(safe, true, 0f)
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            Remote.upload(client, input, size, child(safe), { cancel }) { done, total ->
                                transfer = Transfer(safe, true, if (total > 0) done.toFloat() / total else -1f)
                            }
                        }
                    }
                }.onSuccess { toast(context.getString(if (cancel) R.string.files_cancelled else R.string.files_uploaded, safe)) }
                    .onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                transfer = null
                reload++
            }
        }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roots.forEach { (p, kind) ->
                    FilterChip(
                        selected = path == p || path.startsWith("$p/") && p != "/",
                        onClick = { path = p },
                        leadingIcon = {
                            Icon(
                                when (kind) {
                                    "removable" -> Icons.Default.Usb
                                    "system" -> Icons.Default.Warning
                                    else -> Icons.Default.PhoneAndroid
                                },
                                null,
                            )
                        },
                        label = {
                            Text(
                                when (kind) {
                                    "internal" -> stringResource(R.string.files_internal)
                                    "system" -> stringResource(R.string.files_root)
                                    else -> p.substringAfterLast('/')
                                },
                            )
                        },
                    )
                }
            }
        }
        if (info?.rootFiles == true && path.startsWith("/") && !path.startsWith("/sdcard") && !path.startsWith("/storage")) {
            item { StatusBanner(stringResource(R.string.files_root_warning), false) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = path != "/" && roots.none { it.first == path }, onClick = { path = path.substringBeforeLast('/').ifEmpty { "/" } }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
                Text(path, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.retry)) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(enabled = transfer == null, onClick = { pick.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Upload, null)
                    Text(stringResource(R.string.files_upload))
                }
                FilledTonalButton(onClick = { newFolder = true }) {
                    Icon(Icons.Default.CreateNewFolder, null)
                    Text(stringResource(R.string.files_new_folder))
                }
            }
        }
        transfer?.let { t ->
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (t.upload) R.string.files_sending else R.string.files_receiving, t.name),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { cancel = true }) { Text(stringResource(android.R.string.cancel)) }
                    }
                    if (t.progress >= 0) LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (error.isNotEmpty()) item { StatusBanner(error, false) }
        if (entries == null && error.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        entries?.let { list ->
            if (list.isEmpty()) item { Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.name }) { f ->
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (f.dir) null else Labels.bytes(f.size),
                                if (f.mtime > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(f.mtime * 1000)) else null,
                            ).joinToString(" · "),
                        )
                    },
                    leadingContent = {
                        Icon(if (f.dir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = if (f.dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = {
                        IconButton(onClick = { menu = f }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(expanded = menu == f, onDismissRequest = { menu = null }) {
                            if (!f.dir) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.files_download)) },
                                    leadingIcon = { Icon(Icons.Default.Download, null) },
                                    enabled = transfer == null,
                                    onClick = {
                                        menu = null
                                        pendingDownload = f
                                        save.launch(f.name)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_rename)) },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                                onClick = {
                                    menu = null
                                    renaming = f
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.files_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = {
                                    menu = null
                                    deleting = f
                                },
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier.clip(MaterialTheme.shapes.medium).clickable {
                            if (f.dir) {
                                path = child(f.name)
                            } else if (transfer == null) {
                                pendingDownload = f
                                save.launch(f.name)
                            }
                        },
                )
            }
        }
    }

    renaming?.let { f ->
        NameDialog(stringResource(R.string.files_rename), f.name, { renaming = null }) { name ->
            renaming = null
            scope.launch {
                runCatching { Remote.simple(client, "fs.rename", "from" to child(f.name), "to" to child(name.replace('/', '_'))) }
                    .onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                reload++
            }
        }
    }
    if (newFolder) {
        NameDialog(stringResource(R.string.files_new_folder), "", { newFolder = false }) { name ->
            newFolder = false
            scope.launch {
                runCatching { Remote.simple(client, "fs.mkdir", "path" to child(name.replace('/', '_'))) }
                    .onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                reload++
            }
        }
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(stringResource(R.string.files_delete_title, f.name)) },
            text = { Text(stringResource(if (f.dir) R.string.files_delete_dir else R.string.files_delete_file)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { Remote.simple(client, "fs.delete", "path" to child(f.name), timeoutMs = 120_000) }
                            .onFailure { toast(context.getString(R.string.files_failed, it.message ?: "")) }
                        reload++
                    }
                }) { Text(stringResource(R.string.files_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank() && text != "." && text != "..", onClick = { onDone(text.trim()) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
