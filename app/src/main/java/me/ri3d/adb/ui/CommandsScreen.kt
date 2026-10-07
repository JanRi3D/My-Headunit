package me.ri3d.adb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ri3d.adb.App
import me.ri3d.adb.adb.Foreground
import me.ri3d.adb.console.Kind

private sealed interface StopFlow {
    data object Identifying : StopFlow
    data class Found(val fg: Foreground) : StopFlow
    data class Pick(val note: String?, val packages: List<String>?) : StopFlow
}

@Composable
fun CommandsScreen(app: App) {
    val console = app.console
    val lines by console.lines.collectAsStateWithLifecycle()
    val running by console.running.collectAsStateWithLifecycle()
    var cmd by rememberSaveable { mutableStateOf("") }
    var stop by remember { mutableStateOf<StopFlow?>(null) }
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    fun send() {
        if (cmd.isBlank() || running) return
        console.send(cmd)
        cmd = ""
    }

    fun pick(note: String?) {
        stop = StopFlow.Pick(note, null)
        scope.launch {
            val list = runCatching { console.packages() }.getOrElse { emptyList() }
            if (stop is StopFlow.Pick) stop = StopFlow.Pick(note, list)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Sent to the car over ADB", modifier = Modifier.weight(1f))
            if (lines.isNotEmpty()) GhostButton("Clear", { console.clear() })
        }
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) { Heading("Commands") }

        Box(
            Modifier
                .padding(top = 8.dp)
                .weight(1f)
                .fillMaxWidth()
                .background(C.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, C.Line, RoundedCornerShape(14.dp))
                .semantics { contentDescription = "Command output" },
        ) {
            if (lines.isEmpty()) {
                Text(
                    "Commands run in the car's shell. Their output appears here.",
                    style = T.console, color = C.Faint, modifier = Modifier.padding(14.dp),
                )
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                    items(lines.asReversed(), key = { it.id }) { l ->
                        val color = when (l.kind) {
                            Kind.Cmd -> C.Text
                            Kind.Ok -> C.Green
                            Kind.Err, Kind.Fail -> C.Red
                            Kind.Info -> C.Faint
                            Kind.Out -> C.Muted
                        }
                        Text(
                            buildAnnotatedString {
                                if (l.kind == Kind.Cmd) withStyle(SpanStyle(color = C.Muted)) { append("$ ") }
                                append(l.text)
                            },
                            style = T.console, color = color,
                            modifier = Modifier.padding(top = if (l.kind == Kind.Cmd) 12.dp else 2.dp),
                        )
                    }
                }
            }
        }

        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Stop app on screen", {
                stop = StopFlow.Identifying
                scope.launch {
                    val found = console.identifyForeground()
                    if (stop != StopFlow.Identifying) return@launch
                    val fg = found.getOrNull()
                    if (fg != null) stop = StopFlow.Found(fg)
                    else pick(found.exceptionOrNull()?.message?.let { "Couldn't ask the car: $it" } ?: "Couldn't tell which app is in front. Choose it:")
                }
            }, icon = Icons.Stop, enabled = !running)
            ChipButton("Start FileExplorer", { console.startFileExplorer() }, icon = Icons.Play, enabled = !running)
        }

        Row(
            Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
                .height(52.dp)
                .background(C.Surface, RoundedCornerShape(14.dp))
                .border(1.dp, C.Outline, RoundedCornerShape(14.dp))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("adb shell", style = T.meta.copy(fontSize = 13.sp, lineHeight = 20.sp))
            BasicTextField(
                value = cmd,
                onValueChange = { cmd = it },
                singleLine = true,
                textStyle = T.meta.copy(fontSize = 13.sp, lineHeight = 20.sp, color = C.Text),
                cursorBrush = SolidColor(C.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Shell command" },
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                        if (cmd.isEmpty()) Text("type a command", style = T.meta.copy(fontSize = 13.sp), color = C.Faint)
                        inner()
                    }
                },
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (running || cmd.isNotBlank()) C.Text else C.Line)
                    .clickable(role = Role.Button) { if (running) console.stop() else send() }
                    .semantics { contentDescription = if (running) "Stop the running command" else "Send command" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (running) Icons.Stop else Icons.ArrowUp, null, Modifier.size(20.dp), tint = if (running || cmd.isNotBlank()) C.Bg else C.Faint)
            }
        }
        if (imeVisible) Spacer(Modifier.height(8.dp)) else Spacer(Modifier.navigationBarsPadding().height(NavReserve - 8.dp))
    }

    when (val s = stop) {
        null -> Unit
        StopFlow.Identifying -> SimpleDialog("Stop app on screen", onDismiss = { stop = null }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Spinner()
                Text("Asking the car which app is in front…", style = T.small)
            }
        }
        is StopFlow.Found -> {
            val home = "launcher" in s.fg.packageName || s.fg.packageName == "com.android.systemui"
            Dialog(onDismissRequest = { stop = null }) {
                DialogBox {
                    Heading("Stop ${s.fg.packageName}?", style = T.dialogTitle)
                    Text(
                        (if (home) "This looks like the home screen or system UI. " else "This app is in front on the car. ") +
                            "Force-stopping closes it at once; anything unsaved in it is lost.",
                        style = T.small,
                    )
                    MetaText("Found via ${s.fg.how}: ${s.fg.component ?: s.fg.packageName}", maxLines = 3)
                    GhostButton("Choose another app", { pick(null) })
                    DialogButtons("Stop", destructive = true, onCancel = { stop = null }) {
                        stop = null
                        console.forceStop(s.fg.packageName, s.fg.how)
                    }
                }
            }
        }
        is StopFlow.Pick -> {
            var chosen by remember(s) { mutableStateOf<String?>(null) }
            Dialog(onDismissRequest = { stop = null }) {
                DialogBox {
                    Heading("Which app should stop?", style = T.dialogTitle)
                    s.note?.let { Text(it, style = T.small) }
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        when {
                            s.packages == null -> Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) { Spinner() }
                            s.packages.isEmpty() -> Text("No user-installed apps found on the car.", style = T.small)
                            else -> s.packages.forEach { pkg ->
                                val sel = pkg == chosen
                                HorizontalDivider(color = C.Line)
                                Row(
                                    Modifier.fillMaxWidth().height(48.dp).selectable(sel, role = Role.RadioButton) { chosen = pkg },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(Modifier.size(18.dp).border(2.dp, if (sel) C.Text else C.Dashed, CircleShape), contentAlignment = Alignment.Center) {
                                        if (sel) Box(Modifier.size(7.dp).background(C.Text, CircleShape))
                                    }
                                    Text(pkg, style = T.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    DialogButtons("Stop", destructive = true, enabled = chosen != null, onCancel = { stop = null }) {
                        stop = null
                        chosen?.let { console.forceStop(it, null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogBox(content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(326.dp)
            .background(C.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SimpleDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogBox {
            Heading(title, style = T.dialogTitle)
            content()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { GhostButton("Cancel", onDismiss) }
        }
    }
}

@Composable
private fun DialogButtons(confirm: String, destructive: Boolean, enabled: Boolean = true, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineButton("Cancel", onCancel, Modifier.weight(1f))
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (!enabled) C.Line else if (destructive) C.Red else C.Text)
                .clickable(enabled = enabled, role = Role.Button, onClick = onConfirm),
            contentAlignment = Alignment.Center,
        ) { Text(confirm, style = T.button, color = if (enabled) C.Bg else C.Faint) }
    }
}
