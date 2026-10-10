package me.ri3d.adb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ri3d.adb.App
import me.ri3d.adb.adb.InstalledVersion
import me.ri3d.adb.apps.AddResult
import me.ri3d.adb.apps.CarPackages
import me.ri3d.adb.apps.ManagedApp
import me.ri3d.adb.apps.Picked
import me.ri3d.adb.apps.incompatibility
import me.ri3d.adb.car.CarState
import me.ri3d.adb.car.installedVersions

@Composable
fun AddAppScreen(app: App, target: ManagedApp?, onBack: () -> Unit) {
    val result by app.apps.addResult.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconBtn(Icons.Back, "Back to the car", onBack, Modifier.offset(x = (-12).dp), tint = C.Text, iconSize = 24.dp)
            Heading(if (target != null) "Install ${target.name}" else "Install an app", style = T.h2)
        }
        val done = result as? AddResult.Done
        if (done != null) {
            DoneView(done, onBack = { app.apps.addResult.value = null; onBack() })
        } else {
            Form(app, target)
        }
    }
}

@Composable
private fun DoneView(done: AddResult.Done, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 24.dp)) {
        Column(Modifier.padding(top = 24.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(C.Green)
                Heading("Added to your apps")
            }
            Text(done.detail, style = T.small, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        PrimaryButton("Back to the car", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Form(app: App, target: ManagedApp?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val op by app.apps.addOp.collectAsStateWithLifecycle()
    val running = op != null
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(48.dp)
                .border(1.dp, C.Outline, RoundedCornerShape(14.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf("APK from phone", "Already on the car").forEachIndexed { i, label ->
                val sel = tab == i
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) C.Text else C.Bg)
                        .selectable(selected = sel, enabled = !running, role = Role.Tab) { tab = i },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = if (sel) C.Bg else C.Text2) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 20.dp)) {
            if (tab == 0) ApkTab(app, target) else CarTab(app)
        }
    }
}

// ---------------------------------------------------------------- APK from phone

/** File picker types; some file managers report APKs as plain binary or zip. */
val ApkTypes = arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip")

@Composable
private fun ApkTab(app: App, target: ManagedApp?) {
    val picked by app.apps.picked.collectAsStateWithLifecycle()
    val op by app.apps.addOp.collectAsStateWithLifecycle()
    val result by app.apps.addResult.collectAsStateWithLifecycle()
    val carState by app.car.state.collectAsStateWithLifecycle()
    val car = (carState as? CarState.Connected)?.info
    val managed by app.apps.apps.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) app.apps.pick(uri) }
    fun choose() = pick.launch(ApkTypes)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val p = picked) {
            Picked.None, Picked.Reading -> Column(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .dashedBorder(C.Dashed, 16.dp)
                    .clickable(enabled = p == Picked.None, role = Role.Button, onClick = ::choose)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.padding(bottom = 4.dp).size(56.dp).background(C.Line, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    if (p == Picked.Reading) Spinner(22.dp) else Icon(Icons.Package, null, Modifier.size(26.dp), tint = C.Text)
                }
                Text(if (p == Picked.Reading) "Reading the APK…" else "Choose an APK file", style = T.bodyStrong)
                Text("Opens this phone's file picker", style = T.small)
            }
            is Picked.Invalid -> {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, C.Outline, RoundedCornerShape(14.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Dot(C.Red)
                        Text("Can't install this file", style = T.body)
                    }
                    Text(p.reason, style = T.small)
                }
                OutlineButton("Choose another file", ::choose, Modifier.fillMaxWidth())
            }
            is Picked.Ready -> {
                val apk = p.apk
                val m = apk.manifest
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .background(C.Surface, RoundedCornerShape(14.dp))
                        .border(1.dp, C.Outline, RoundedCornerShape(14.dp))
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).background(C.Line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Package, null, Modifier.size(22.dp), tint = C.Text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(apk.displayName, style = T.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MetaText("${formatSize(apk.size)} · ${m.versionName ?: "?"} (${m.versionCode})")
                    }
                    GhostButton("Change", { app.apps.unpick() }, enabled = op == null)
                }

                val onCar by produceState<InstalledVersion?>(null, m.packageName, car?.session) {
                    value = runCatching { app.car.installedVersions(listOf(m.packageName))[m.packageName] }.getOrNull()
                }
                val problem = car?.let { incompatibility(m.minSdk, apk.abis, it.sdk, it.release, it.abis) }
                    ?: onCar?.takeIf { it.versionCode > m.versionCode }?.let { "The car has a newer version (${it.versionName ?: it.versionCode}); downgrades are refused" }
                InfoLine(
                    dot = if (car == null) C.Dashed else if (problem == null) C.Green else C.Amber,
                    title = when {
                        car == null -> "Not connected to the car"
                        problem == null -> "Works on this head unit"
                        else -> "Can't be installed on this head unit"
                    },
                    meta = problem ?: car?.let { "Needs Android ${me.ri3d.adb.apps.androidName(m.minSdk ?: 1)}+ · car runs ${it.release}" } ?: "Connect to check compatibility",
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("App")
                    Text(apk.label ?: m.packageName, style = T.body)
                    MetaText(m.packageName)
                    MetaText(
                        (if (apk.abis.isEmpty()) "No native code" else "Native code: ${apk.abis.sorted().joinToString()}") +
                            (onCar?.let { " · on the car: ${it.versionName ?: it.versionCode}" } ?: ""),
                    )
                    car?.let { MetaText("Target: ${it.name} · ${it.endpoint}") }
                }

                val existing = managed.firstOrNull { it.packageName == m.packageName }
                if (target != null && target.packageName != m.packageName) {
                    MetaText("This APK is ${m.packageName}, not ${target.name} (${target.packageName}).", C.Amber)
                }
                var name by rememberSaveable(m.packageName) { mutableStateOf(existing?.name ?: apk.label ?: m.packageName) }
                Field(name, { name = it }, "Name in the app list", enabled = existing == null && op == null)
                if (existing != null) MetaText("Already in your apps as ${existing.name}")
                var openAfter by rememberSaveable { mutableStateOf(app.settings.openAfterInstall) }
                SwitchRow("Open on the car after installing", openAfter) { openAfter = it }

                InstallFooter(
                    app,
                    ready = car != null && problem == null,
                    cta = "Install on the car",
                    onClick = { app.apps.installPicked(name.trim(), openAfter) },
                )
            }
        }
        if (picked !is Picked.Ready) (result as? AddResult.Failed)?.let { MetaText(it.reason, C.Amber, maxLines = 6) }
    }
}

@Composable
private fun InfoLine(dot: androidx.compose.ui.graphics.Color, title: String, meta: String) {
    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(horizontal = 5.dp)) { Dot(dot) }
        Column {
            Text(title, style = T.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)))
            MetaText(meta, maxLines = 3)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        HorizontalDivider(color = C.Line)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(checked, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, style = T.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)), modifier = Modifier.weight(1f))
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = C.Text, checkedThumbColor = C.Bg, checkedBorderColor = C.Text,
                    uncheckedTrackColor = C.Bg, uncheckedThumbColor = C.Muted, uncheckedBorderColor = C.Dashed,
                ),
            )
        }
        HorizontalDivider(color = C.Line)
    }
}

/** The bottom action: the button, or transfer/installation progress while it runs, or the last failure. */
@Composable
private fun InstallFooter(app: App, ready: Boolean, cta: String, onClick: () -> Unit) {
    val op by app.apps.addOp.collectAsStateWithLifecycle()
    val result by app.apps.addResult.collectAsStateWithLifecycle()
    val o = op
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (result as? AddResult.Failed)?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp)) { Dot(C.Red) }
                Text(it.reason, style = T.small.copy(color = C.Text2), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (o == null) {
            PrimaryButton(if (result is AddResult.Failed) "Try again" else cta, onClick, Modifier.fillMaxWidth(), enabled = ready)
        } else {
            val p by o.progress.collectAsStateWithLifecycle()
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaText(
                        p.phase + if (p.total > 0) " ${formatSize(p.done)} / ${formatSize(p.total)}" else "",
                        C.Text2, Modifier.weight(1f),
                    )
                    GhostButton("Cancel", { o.cancel() })
                }
                ProgressLine(p.fraction)
            }
        }
    }
}

// ---------------------------------------------------------------- already on the car

@Composable
private fun CarTab(app: App) {
    val pkgs by app.apps.carPackages.collectAsStateWithLifecycle()
    val managed by app.apps.apps.collectAsStateWithLifecycle()
    val connected = app.car.state.collectAsStateWithLifecycle().value is CarState.Connected
    LaunchedEffect(connected) { if (connected) app.apps.loadCarPackages() }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Installed on the head unit", heading = true)
            when (val p = pkgs) {
                CarPackages.Loading -> Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spinner()
                    Text(if (connected) "Asking the car…" else "Not connected to the car", style = T.small)
                }
                is CarPackages.Failed -> {
                    MetaText(p.reason, C.Amber)
                    OutlineButton("Try again", { app.apps.loadCarPackages() }, enabled = connected)
                }
                is CarPackages.Loaded -> {
                    if (p.packages.size > 8) Field(filter, { filter = it }, "Filter", mono = true, placeholder = "com.example")
                    val shown = p.packages.filter { filter.isBlank() || it.contains(filter.trim(), true) }
                    if (shown.isEmpty()) Text(if (p.packages.isEmpty()) "No user-installed apps on the car." else "Nothing matches.", style = T.small)
                    Column {
                        shown.forEach { pkg ->
                            val inList = managed.any { it.packageName == pkg }
                            val sel = chosen == pkg
                            HorizontalDivider(color = C.Line)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .selectable(sel, enabled = !inList, role = Role.RadioButton) {
                                        chosen = pkg
                                        if (name.isBlank() || name == chosen) name = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(Modifier.size(20.dp).border(2.dp, if (sel) C.Text else C.Dashed, CircleShape), contentAlignment = Alignment.Center) {
                                    if (sel) Box(Modifier.size(8.dp).background(C.Text, CircleShape))
                                }
                                Text(
                                    pkg, style = T.mono.copy(fontWeight = FontWeight.Normal), color = if (inList) C.Faint else if (sel) C.Text else C.Text2,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                if (inList) MetaText("In your apps")
                            }
                        }
                        HorizontalDivider(color = C.Line)
                    }
                }
            }
        }
        Field(name, { name = it }, "Name in the app list")
        PrimaryButton("Add to app list", { chosen?.let { app.apps.addExisting(it, name.trim()) } }, Modifier.fillMaxWidth(), enabled = chosen != null)
    }
}
