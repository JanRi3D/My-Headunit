package me.ri3d.adb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ri3d.adb.App
import me.ri3d.adb.adb.RemoteFile
import me.ri3d.adb.files.FilesModel
import me.ri3d.adb.files.Listing
import me.ri3d.adb.files.Outgoing
import me.ri3d.adb.files.childOf
import me.ri3d.adb.files.parentOf
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(app: App) {
    val model = app.files
    val path by model.path.collectAsStateWithLifecycle()
    val listing by model.listing.collectAsStateWithLifecycle()
    val transfer by model.transfer.collectAsStateWithLifecycle()
    var selected by rememberSaveable(path) { mutableStateOf(emptyList<String>()) }
    var askDelete by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf<Pair<List<Outgoing>, List<String>>?>(null) }
    val scope = rememberCoroutineScope()
    val entries = (listing as? Listing.Loaded)?.entries.orEmpty()
    val selection = entries.filter { it.name in selected }
    LaunchedEffect(entries) { selected = selected.filter { n -> entries.any { it.name == n } } }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            try {
                val (items, existing) = model.prepareUpload(uris)
                if (existing.isEmpty()) model.upload(items, emptySet()) else conflicts = items to existing
            } catch (e: Exception) {
                app.say("Can't upload: ${e.message}")
            }
        }
    }
    val saveOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = selection.singleOrNull()
        if (uri != null && f != null) {
            model.download(listOf(f to uri))
            selected = emptyList()
        }
    }
    val saveMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            model.downloadToFolder(selection, tree)
            selected = emptyList()
        }
    }

    BackHandler(enabled = selected.isNotEmpty() || path != "/") {
        if (selected.isNotEmpty()) selected = emptyList() else model.open(parentOf(path))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Car storage over ADB", modifier = Modifier.weight(1f))
                IconBtn(Icons.Refresh, "Refresh", { model.refresh() }, Modifier.offset(x = 10.dp), iconSize = 20.dp)
            }
            Box(Modifier.height(48.dp)) {
                if (selection.isEmpty()) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { Heading("Files") }
                } else {
                    SelectionBar(
                        count = selection.size,
                        onClear = { selected = emptyList() },
                        onDownload = {
                            if (selection.size == 1) saveOne.launch(selection[0].name) else saveMany.launch(null)
                        },
                        onDelete = { askDelete = true },
                        busy = transfer != null,
                    )
                }
            }
            Breadcrumbs(path) { model.open(it) }
            HorizontalDivider(color = C.Line)
            PullToRefreshBox(
                isRefreshing = listing is Listing.Loading,
                onRefresh = { model.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when (val l = listing) {
                    Listing.Loading -> Box(Modifier.fillMaxSize())
                    is Listing.Failed -> CenterNote {
                        Text("Can't open this folder", style = T.bodyStrong)
                        Text(l.reason, style = T.small, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (l.path != "/") OutlineButton("Go up", { model.open(parentOf(l.path)) })
                            OutlineButton("Try again", { model.refresh() })
                        }
                    }
                    is Listing.Loaded -> if (l.entries.isEmpty()) {
                        CenterNote {
                            Icon(Icons.Folder, null, Modifier.padding(bottom = 4.dp).size(28.dp), tint = C.Dashed)
                            Text("This folder is empty", style = T.bodyStrong)
                            Text("Upload a file from this phone to put it here.", style = T.small, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp))
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(l.entries, key = { it.name }) { f ->
                                if (f.isDir || f.isLink) {
                                    FolderRow(f) { model.open(childOf(l.path, f.name)) }
                                } else {
                                    FileRow(f, f.name in selected) {
                                        selected = if (f.name in selected) selected - f.name else selected + f.name
                                    }
                                }
                                HorizontalDivider(color = C.Line)
                            }
                            item { Spacer(Modifier.navigationBarsPadding().height(NavReserve + 64.dp)) }
                        }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = NavReserve - 4.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            transfer?.let { TransferCard(it) }
            if (selection.isEmpty() && transfer == null) {
                Row(
                    Modifier
                        .shadow(20.dp, RoundedCornerShape(14.dp))
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(C.Text)
                        .clickable(role = Role.Button) { uploadPicker.launch(arrayOf("*/*")) }
                        .padding(start = 14.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Upload, null, Modifier.size(20.dp), tint = C.Bg)
                    Text("Upload", style = T.button, color = C.Bg)
                }
            }
        }
    }

    if (askDelete && selection.isNotEmpty()) {
        ConfirmDialog(
            title = "Delete ${FilesModel.plural(selection.size)}?",
            body = "Deleted files are removed from the car's storage. This can't be undone.",
            confirm = "Delete",
            destructive = true,
            onConfirm = {
                askDelete = false
                model.delete(selection) { selected = emptyList() }
            },
            onDismiss = { askDelete = false },
        )
    }
    conflicts?.let { (items, existing) ->
        ConfirmDialog(
            title = if (existing.size == 1) "Replace ${existing[0]}?" else "${existing.size} files already exist",
            body = "The folder on the car already has ${if (existing.size == 1) "a file with this name" else existing.joinToString()}. Replace ${if (existing.size == 1) "it" else "them"}?" +
                if (items.size > existing.size) " Cancel uploads only the other files." else "",
            confirm = "Replace",
            onConfirm = {
                conflicts = null
                model.upload(items, emptySet())
            },
            onDismiss = {
                conflicts = null
                model.upload(items, existing.toSet())
            },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit, busy: Boolean) {
    Row(
        Modifier
            .fillMaxSize()
            .offset(x = (-8).dp)
            .fillMaxWidth()
            .background(C.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconBtn(Icons.Close, "Clear selection", onClear, tint = C.Text)
        Text("$count selected", style = T.bodyStrong, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        IconBtn(Icons.Download, "Download to this phone", onDownload, tint = C.Text, enabled = !busy)
        IconBtn(Icons.Trash, "Delete from the car", onDelete, tint = C.Red, enabled = !busy)
    }
}

@Composable
private fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .padding(top = 4.dp)
            .offset(x = (-8).dp)
            .height(44.dp)
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "Current folder $path" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val crumb = T.mono.copy(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        @Composable
        fun item(label: String, target: String, current: Boolean) {
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !current, role = Role.Button) { onOpen(target) }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = crumb, color = if (current) C.Text else C.Muted) }
        }
        item("/", "/", parts.isEmpty())
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("/", style = crumb, color = C.Dashed)
            item(p, "/" + parts.take(i + 1).joinToString("/"), i == parts.lastIndex)
        }
    }
}

@Composable
private fun FolderRow(f: RemoteFile, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button, onClickLabel = "Open folder", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(if (f.isLink) Icons.Link else Icons.Folder, null, Modifier.size(20.dp), tint = C.Text2)
        }
        Text(f.name, style = T.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Chevron, null, Modifier.size(18.dp), tint = C.Muted)
    }
}

@Composable
private fun FileRow(f: RemoteFile, selected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(selected, role = Role.Checkbox) { onToggle() }
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(24.dp).background(if (selected) C.Text else C.Bg, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Check, null, Modifier.size(16.dp), tint = C.Bg) else Icon(Icons.File, null, Modifier.size(20.dp), tint = C.Muted)
        }
        Column(Modifier.weight(1f)) {
            Text(f.name, style = T.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaText("${formatSize(f.size)} · ${typeOf(f.name)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.mtimeSec * 1000))}")
        }
    }
}

private fun typeOf(name: String) = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 6 && it != name }?.uppercase() ?: "File"

@Composable
private fun TransferCard(op: me.ri3d.adb.Op) {
    val p by op.progress.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .background(C.Line, RoundedCornerShape(12.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(p.phase, style = T.small.copy(color = C.Text, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(if (p.total > 0) "${formatSize(p.done)} / ${formatSize(p.total)}" else formatSize(p.done))
            }
            GhostButton("Cancel", { op.cancel() })
        }
        Box(Modifier.padding(end = 12.dp)) { ProgressLine(p.fraction) }
    }
}

@Composable
private fun CenterNote(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
