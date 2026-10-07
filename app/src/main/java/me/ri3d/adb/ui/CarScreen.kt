package me.ri3d.adb.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.ri3d.adb.App
import me.ri3d.adb.Settings
import me.ri3d.adb.apps.AppRow
import me.ri3d.adb.apps.CheckState
import me.ri3d.adb.apps.ManagedApp
import me.ri3d.adb.apps.iconFile
import me.ri3d.adb.car.CarInfo
import me.ri3d.adb.car.CarState
import me.ri3d.adb.car.Endpoint
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------- not connected

@Composable
fun ConnectScreen(app: App, state: CarState) {
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf(app.settings.endpoint) }
    val valid = Endpoint.parse(address) != null
    val busy = state is CarState.Connecting || state is CarState.AuthorizationRequired
    fun tryAgain() {
        if (!valid) return
        app.settings.endpoint = Endpoint.parse(address).toString()
        app.car.connect()
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        val minH = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minH)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) { Label("My Headunit", heading = true) }
                VSpace(28.dp)
                Radar(state)
                VSpace(24.dp)
                ConnectionText(state)
                OfflineUpdates(app)
            }
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Field(
                    value = address, onValueChange = { address = it }, label = "Car address", mono = true,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, onIme = ::tryAgain,
                )
                if (!valid) MetaText("Use host:port, e.g. ${Settings.DEFAULT_ENDPOINT}", C.Amber, Modifier.padding(top = 6.dp))
                VSpace(14.dp)
                Column(Modifier.heightIn(min = 104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (busy) {
                        OutlineButton("Cancel", { app.car.cancel() }, Modifier.fillMaxWidth())
                    } else {
                        PrimaryButton("Open Wi-Fi settings", { context.startActivity(Intent(AndroidSettings.ACTION_WIFI_SETTINGS)) }, Modifier.fillMaxWidth())
                        OutlineButton("Try again", ::tryAgain, Modifier.fillMaxWidth(), enabled = valid)
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(NavReserve))
            }
        }
    }
}

@Composable
private fun ConnectionText(state: CarState) {
    val now by ticker()
    val (title, sub) = when (state) {
        CarState.Disconnected -> "Not connected" to "Disconnected"
        is CarState.Connecting -> "Connecting" to state.stage
        is CarState.AuthorizationRequired -> "Allow on the car" to "Confirm USB debugging on the car screen"
        is CarState.Failed -> "Connection failed" to (state.retryAt?.let { "Retrying in ${secondsUntil(it, now)} s" } ?: "Not retrying")
        is CarState.Lost -> "Connection lost" to (state.retryAt?.let { "Reconnecting in ${secondsUntil(it, now)} s" } ?: "Not reconnecting")
        is CarState.Connected -> "Connected" to "ADB ready"
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Heading(title, modifier = Modifier)
        Label(sub, style = T.label.copy(letterSpacing = 1.2.sp))
        val detail = when (state) {
            is CarState.Failed -> state.reason
            is CarState.Lost -> state.reason
            else -> null
        }
        if (detail != null) Text(detail, style = T.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp))
        if (state is CarState.AuthorizationRequired) {
            Text(
                "If the car's screen asks “Allow USB debugging?”, tick “Always allow from this computer” and tap Allow.",
                style = T.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
            )
            MetaText("Key fingerprint\n${state.fingerprint}", modifier = Modifier.padding(top = 4.dp), maxLines = 3)
        }
    }
}

private fun secondsUntil(at: Long, now: Long) = ((at - now + 999) / 1000).coerceAtLeast(0)

@Composable
private fun ticker() = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1000)
        value = System.currentTimeMillis()
    }
}

@Composable
private fun Radar(state: CarState) {
    val active = state is CarState.Connecting || state is CarState.AuthorizationRequired
    val pinging = state !is CarState.Failed && state != CarState.Disconnected
    val period = if (state is CarState.Connecting) 1200 else 2800
    val ping = if (active) C.Text else C.Dashed
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val r = size.minDimension / 2
            drawCircle(C.Line, r - 0.5f, style = Stroke(1.dp.toPx()))
            drawCircle(C.Line, r * 152f / 220f - 0.5f, style = Stroke(1.dp.toPx()))
            if (pinging) {
                for (phase in floatArrayOf(p, (p + 0.5f) % 1f)) {
                    val e = FastOutSlowInEasing.transform(phase)
                    drawCircle(ping.copy(alpha = 0.9f * (1 - e)), r * (0.3f + 0.7f * e), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Box(
            Modifier
                .size(72.dp)
                .background(C.Surface, CircleShape)
                .border(1.dp, if (active) C.Text else C.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Car, null, Modifier.size(30.dp), tint = if (active) C.Text else C.Muted)
        }
    }
}

// ---------------------------------------------------------------- connected

@Composable
fun CarHome(app: App, info: CarInfo, onAddApp: (ManagedApp?) -> Unit) {
    val rows by app.apps.rows.collectAsStateWithLifecycle()
    val pending by app.apps.pending.collectAsStateWithLifecycle()
    val check by app.apps.check.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<AppRow?>(null) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Header(check, onRefresh = { app.apps.refresh() })
        ConnectionSummary(info, onDisconnect = { app.car.disconnect() })

        val installedCount = rows.count { it.installed != null }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("On the car", style = T.tag)
            Label(if (rows.any { it.installedKnown }) "$installedCount of ${rows.size} installed" else "Reading…", style = T.tag)
        }
        VSpace(14.dp)
        TileGrid(rows)

        if (pending.isNotEmpty() && check != CheckState.Checking) {
            UpdateCard(pending, onNotNow = { app.apps.notNow() }, onUpdate = { app.apps.installReleases(pending.map { it.app }) })
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Apps on the car", heading = true)
            ChipButton("APK file", { onAddApp(null) }, style = Chip.Dashed, icon = Icons.Plus, description = "Install an APK file")
        }
        if (check is CheckState.Failed) {
            MetaText("Update check failed: ${(check as CheckState.Failed).reason}", C.Amber, Modifier.padding(bottom = 8.dp), maxLines = 3)
        }
        rows.forEach { row ->
            AppRowView(
                row,
                onOpen = { app.apps.launch(row.app) },
                onInstall = { confirm = row },
                onDetails = { details = row.app.packageName },
                onCancel = { row.op?.cancel() },
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(NavReserve))
    }

    confirm?.let { row ->
        val r = row.release!!
        val update = row.installed != null
        ConfirmDialog(
            title = if (update) "Update ${row.app.name} on the car?" else "Install ${row.app.name} on the car?",
            body = buildString {
                append(if (update) "${versionLabel(row)} → ${r.label}" else r.label)
                append(
                    if (row.ready) " · already downloaded (${formatSize(r.size)}), no internet needed."
                    else " · ${formatSize(r.size)} download from ${row.app.source?.describe()}.",
                )
                if (update) append(" The app keeps its data.")
            },
            confirm = if (update) "Update" else "Install",
            onConfirm = { confirm = null; app.apps.installReleases(listOf(row.app)) },
            onDismiss = { confirm = null },
        )
    }
    details?.let { pkg ->
        rows.firstOrNull { it.app.packageName == pkg }?.let { row ->
            AppSheet(app, row, onDismiss = { details = null }, onInstallFile = { details = null; onAddApp(row.app) })
        }
    }
}

@Composable
private fun Header(check: CheckState, onRefresh: () -> Unit) {
    val now by ticker()
    val label = checkLabel(check, now)
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("My Headunit", Modifier.weight(1f), heading = true)
        Label(label, style = T.tag, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        val t = rememberInfiniteTransition(label = "spin")
        val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
        IconBtn(
            Icons.Refresh, "Check for updates", onRefresh, Modifier.offset(x = 10.dp), iconSize = 20.dp,
            enabled = check != CheckState.Checking, iconModifier = if (check == CheckState.Checking) Modifier.rotate(angle) else Modifier,
        )
    }
}

@Composable
private fun ConnectionSummary(info: CarInfo, onDisconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(C.Green)
            Heading("Connected", modifier = Modifier.weight(1f))
            GhostButton("Disconnect", onDisconnect, Modifier.offset(x = 12.dp))
        }
        MetaText("${info.endpoint} · ${info.network} · ${if (info.authenticated) "ADB allowed" else "ADB (no auth)"}")
        MetaText("${info.name} · Android ${info.release} · API ${info.sdk} · ${info.abis.firstOrNull() ?: "?"}${if (info.legacyShell) " · legacy shell" else ""}")
    }
}

@Composable
private fun TileGrid(rows: List<AppRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line.forEach { row ->
                    val installed = row.installed != null
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppTile(row.app.monogram, rememberIcon(row.app.packageName, row.installed?.versionCode), installed) {
                            when {
                                row.op != null -> Box(Modifier.offset(6.dp, (-6).dp).size(22.dp).background(C.Bg, CircleShape), contentAlignment = Alignment.Center) { Spinner() }
                                row.updateAvailable -> Box(
                                    Modifier.offset(6.dp, (-6).dp).size(22.dp).border(2.dp, C.Bg, CircleShape).background(C.Text, CircleShape)
                                        .semantics { contentDescription = "Update available" },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.ArrowUpBold, null, Modifier.size(12.dp), tint = C.Bg) }
                            }
                        }
                        Text(
                            row.app.name, style = T.small.copy(fontSize = 11.sp, lineHeight = 14.sp), color = if (installed) C.Text2 else C.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun rememberIcon(pkg: String, version: Long?): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg, version) {
        value = withContext(Dispatchers.IO) {
            iconFile(context, pkg).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    return icon
}

@Composable
private fun UpdateCard(pending: List<AppRow>, onNotNow: () -> Unit, onUpdate: () -> Unit) {
    Column(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .border(1.dp, C.Outline, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, top = 14.dp, end = 10.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Label(if (pending.size == 1) "Update available" else "${pending.size} updates available", style = T.label.copy(letterSpacing = 1.2.sp), heading = true)
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            pending.forEach { row ->
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(row.app.name, style = T.bodyStrong)
                    Text("${versionLabel(row)} → ${row.release!!.label}", style = T.meta.copy(fontSize = 13.sp, lineHeight = 20.sp), color = C.Text2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("Not now", onNotNow)
            Box(
                Modifier
                    .height(44.dp)
                    .clickable(role = Role.Button, onClick = onUpdate)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.height(40.dp).background(C.Text, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text("Update on the car", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = C.Bg)
                }
            }
        }
    }
}

private fun versionLabel(row: AppRow) = row.installed?.let { it.versionName ?: "build ${it.versionCode}" } ?: "—"

private fun checkLabel(check: CheckState, now: Long) = when (check) {
    CheckState.Checking -> "Checking…"
    is CheckState.Failed -> "Check failed"
    is CheckState.Idle -> if (check.lastOk == 0L) "Not checked yet" else "Checked ${ago(check.lastOk, now)}"
}

private fun rowMeta(row: AppRow, progress: me.ri3d.adb.Op.Progress?): Pair<String, Color> {
    val (text, color) = rowStatus(row, progress)
    return (if (progress == null && row.ready && (row.updateAvailable || row.installable)) "$text · ready offline" else text) to color
}

private fun rowStatus(row: AppRow, progress: me.ri3d.adb.Op.Progress?): Pair<String, Color> {
    if (progress != null) {
        val pct = progress.fraction?.let { " ${(it * 100).toInt()}%" } ?: ""
        return "${progress.phase}$pct" to C.Muted
    }
    if (!row.installedKnown) return "Reading…" to C.Muted
    val r = row.release
    val noSource = row.app.source == null
    return if (row.installed != null) {
        val v = versionLabel(row)
        when {
            row.updateAvailable -> "$v → ${r!!.label}" to C.Text
            noSource -> "$v · Source not configured" to C.Muted
            row.checkError != null -> "$v · Update check failed" to C.Amber
            r == null -> "$v · Not checked yet" to C.Muted
            row.incompatible != null -> "$v · ${r.label} doesn't fit this car" to C.Muted
            r.versionCode < row.installed.versionCode -> "$v · Newer than ${r.label}" to C.Muted
            else -> "$v · Up to date" to C.Muted
        }
    } else {
        when {
            noSource -> "Not installed · Source not configured" to C.Muted
            row.checkError != null -> "Not installed · Update check failed" to C.Amber
            r == null -> "Not installed · Not checked yet" to C.Muted
            row.incompatible != null -> "${r.label} · ${row.incompatible}" to C.Amber
            else -> "${r.label} · Not installed" to C.Muted
        }
    }
}

@Composable
private fun AppRowView(row: AppRow, onOpen: () -> Unit, onInstall: () -> Unit, onDetails: () -> Unit, onCancel: () -> Unit) {
    val progress = row.op?.progress?.collectAsStateWithLifecycle()?.value
    val (meta, metaColor) = rowMeta(row, progress)
    Column {
        HorizontalDivider(color = C.Line)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(onClickLabel = "Details for ${row.app.name}", onClick = onDetails)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.app.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(meta, metaColor)
                if (row.op != null) ProgressLine(progress?.fraction, Modifier.padding(top = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    row.op != null -> IconBtn(Icons.Close, "Cancel ${row.app.name}", onCancel, Modifier.offset(x = 10.dp))
                    else -> {
                        if (row.installed != null) {
                            IconBtn(Icons.Play, "Open ${row.app.name} on the car screen", onOpen, Modifier.offset(x = if (row.updateAvailable) 0.dp else 10.dp), iconSize = 20.dp)
                        }
                        if (row.updateAvailable) ChipButton("Update", onInstall, style = Chip.Filled, description = "Update ${row.app.name}")
                        if (row.installable) ChipButton("Install", onInstall, description = "Install ${row.app.name}")
                        if (row.installedKnown && row.installed == null && row.app.source == null) ChipButton("Set up", onDetails, description = "Set up ${row.app.name}")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- app details

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(app: App, row: AppRow, onDismiss: () -> Unit, onInstallFile: () -> Unit) {
    var source by rememberSaveable(row.app.packageName) { mutableStateOf(row.app.source?.text().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface, contentColor = C.Text) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Heading(row.app.name, style = T.h2)
                MetaText(row.app.packageName)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("On the car")
                Text(
                    when {
                        !row.installedKnown -> "Unknown until connected"
                        row.installed == null -> "Not installed"
                        else -> "${versionLabel(row)} (versionCode ${row.installed.versionCode})"
                    },
                    style = T.mono,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Available")
                val r = row.release
                when {
                    row.app.source == null -> Text("Source not configured. Add a release source below, or install an APK file.", style = T.small)
                    r != null -> {
                        Text("${r.label} (versionCode ${r.versionCode})", style = T.mono)
                        MetaText(
                            "${row.app.source.describe()} · ${formatSize(r.size)} · needs Android ${me.ri3d.adb.apps.androidName(r.minSdk ?: 1)}+" +
                                (if (r.abis.isNotEmpty()) " · ${r.abis.sorted().joinToString()}" else "") +
                                " · checked ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.checkedAt))}",
                            maxLines = 4,
                        )
                        row.incompatible?.let { MetaText(it, C.Amber) }
                    }
                    row.checkError == null -> Text("Not checked yet", style = T.small)
                }
                row.checkError?.let { MetaText("Last check failed: $it", C.Amber, maxLines = 4) }
            }
            Field(
                value = source, onValueChange = { source = it; error = null }, label = "Update source", mono = true,
                placeholder = "github:Owner/Repo or https://…/app.json", keyboardType = KeyboardType.Uri,
                onIme = { error = app.apps.setSource(row.app, source) },
            )
            error?.let { MetaText(it, C.Amber) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Save source", { error = app.apps.setSource(row.app, source) }, Modifier.weight(1f), enabled = source.trim() != row.app.source?.text().orEmpty())
                OutlineButton("Install APK file", onInstallFile, Modifier.weight(1f))
            }
            if (row.installed != null) PrimaryButton("Open on the car", { app.apps.launch(row.app); onDismiss() }, Modifier.fillMaxWidth())
            if (!row.app.fromCatalog) {
                GhostButton("Remove from the list (stays installed)", { app.apps.remove(row.app); onDismiss() }, color = C.Red)
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(326.dp)
                .background(C.Surface, RoundedCornerShape(16.dp))
                .border(1.dp, C.Outline, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Heading(title, style = T.dialogTitle)
            Text(body, style = T.small)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Cancel", onDismiss, Modifier.weight(1f))
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(if (destructive) C.Red else C.Text, RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) { Text(confirm, style = T.button, color = C.Bg) }
            }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

fun ago(t: Long, now: Long): String {
    val s = (now - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}


// ---------------------------------------------------------------- offline updates

/**
 * Shown while not connected: what the car had at the last visit and which updates exist, with a
 * user-started download so they can be installed at the car without internet.
 */
@Composable
private fun OfflineUpdates(app: App) {
    val car by app.apps.lastCar.collectAsStateWithLifecycle()
    val rows by app.apps.rows.collectAsStateWithLifecycle()
    val check by app.apps.check.collectAsStateWithLifecycle()
    val snap = car ?: return
    val now by ticker()
    val todo = rows.filter { it.updateAvailable || it.installable }
    val missing = todo.filter { !it.ready && it.op == null }
    Column(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .border(1.dp, C.Outline, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Updates for ${snap.name}", heading = true, style = T.label.copy(letterSpacing = 1.2.sp))
                MetaText("Apps as of ${ago(snap.at, now)} · ${checkLabel(check, now).lowercase()}", maxLines = 1)
            }
            IconBtn(Icons.Refresh, "Check for updates", { app.apps.refresh() }, iconSize = 20.dp, enabled = check != CheckState.Checking)
        }
        if (todo.isEmpty()) {
            Text(
                if (check is CheckState.Failed) "Couldn't check: ${(check as CheckState.Failed).reason}"
                else "Nothing to update. The car's apps were up to date at the last visit.",
                style = T.small, modifier = Modifier.padding(end = 10.dp, bottom = 4.dp),
            )
        }
        todo.forEach { row ->
            val p = row.op?.progress?.collectAsStateWithLifecycle()?.value
            Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.app.name, style = T.bodyStrong.copy(fontSize = 15.sp))
                Text(
                    if (row.installed != null) "${versionLabel(row)} → ${row.release!!.label}" else "${row.release!!.label} · new",
                    style = T.meta.copy(fontSize = 13.sp, lineHeight = 20.sp), color = C.Text2, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                when {
                    p != null -> MetaText(p.fraction?.let { "${(it * 100).toInt()}%" } ?: "…")
                    row.ready -> MetaText("Ready", C.Green)
                }
            }
        }
        if (missing.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End) {
                ChipButton(
                    "Download for offline · ${formatSize(missing.sumOf { it.release!!.size })}",
                    { app.apps.downloadForOffline(missing.map { it.app }) },
                    style = Chip.Filled, icon = Icons.Download,
                )
            }
        } else if (todo.isNotEmpty() && todo.all { it.ready }) {
            Text("Downloaded. Connect to the car to install, no internet needed.", style = T.small, modifier = Modifier.padding(end = 10.dp, bottom = 4.dp))
        }
    }
}
