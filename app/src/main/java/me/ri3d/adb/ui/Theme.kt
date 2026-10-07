package me.ri3d.adb.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ri3d.adb.R

// Tokens from the reference design.
object C {
    val Bg = Color(0xFF050506)
    val Surface = Color(0xFF0E0F11)
    val Raised = Color(0xFF16181B)
    val Line = Color(0xFF1F2125)
    val Outline = Color(0xFF2C2F34)
    val Dashed = Color(0xFF5C6068)
    val Text = Color(0xFFF4F5F7)
    val Text2 = Color(0xFFD5D7DB)
    val Muted = Color(0xFF9EA1A8)
    val Faint = Color(0xFF7B7F87)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Amber = Color(0xFFFFB340)
}

private fun variable(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Geist = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.geist, it) })
val GeistMono = FontFamily(listOf(400, 500, 600).map { variable(R.font.geist_mono, it) })

object T {
    val title = TextStyle(fontFamily = Geist, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, color = C.Text)
    val h2 = TextStyle(fontFamily = Geist, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = C.Text)
    val dialogTitle = TextStyle(fontFamily = Geist, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = C.Text)
    val body = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp, color = C.Text)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal, color = C.Muted)
    val button = TextStyle(fontFamily = Geist, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontFamily = GeistMono, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp, color = C.Muted)
    val tag = TextStyle(fontFamily = GeistMono, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp, color = C.Muted)
    val meta = TextStyle(fontFamily = GeistMono, fontSize = 12.sp, lineHeight = 16.sp, color = C.Muted)
    val mono = TextStyle(fontFamily = GeistMono, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = C.Text)
    val console = TextStyle(fontFamily = GeistMono, fontSize = 12.5.sp, lineHeight = 19.sp, color = C.Muted)
}

@Composable
fun HeadunitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Text, onPrimary = C.Bg, background = C.Bg, onBackground = C.Text,
            surface = C.Surface, onSurface = C.Text, surfaceVariant = C.Raised, onSurfaceVariant = C.Muted,
            surfaceContainerLow = C.Surface, surfaceContainer = C.Surface, surfaceContainerHigh = C.Raised,
            outline = C.Outline, outlineVariant = C.Line, error = C.Red, secondary = C.Text2,
        ),
        content = content,
    )
}

// Stroke icons from the reference (24×24 grid, 1.75 stroke, round caps).
object Icons {
    private fun icon(name: String, vararg paths: String, stroke: Float = 1.75f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = stroke, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Refresh = icon("refresh", "M23 4v6h-6M20.49 15a9 9 0 1 1-2.12-9.36L23 10")
    val Car = icon(
        "car", "M5 11l1.6-4.6A2 2 0 0 1 8.5 5h7a2 2 0 0 1 1.9 1.4L19 11",
        "M5 11h14a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 1 2-2z", "M6.5 17v2M17.5 17v2M7.5 14h.01M16.5 14h.01",
    )
    val Folder = icon("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Terminal = icon("terminal", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 9l3 3-3 3M13 15h4")
    val Plus = icon("plus", "M12 5v14M5 12h14")
    val Play = icon("play", "M8 5.5v13l10.5-6.5z")
    val ArrowUp = icon("up", "M12 19V5M6 11l6-6 6 6", stroke = 2f)
    val ArrowUpBold = icon("upBold", "M12 19V5M6 11l6-6 6 6", stroke = 3f)
    val Back = icon("back", "M15 6l-6 6 6 6")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Download = icon("download", "M12 4v12M7 11l5 5 5-5M4 20h16")
    val Trash = icon("trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6")
    val File = icon("file", "M6 3h8l5 5v13H6zM14 3v5h5")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5", stroke = 3f)
    val Chevron = icon("chevron", "M9 6l6 6-6 6")
    val Stop = icon("stop", "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z")
    val Upload = icon("upload", "M12 16V4M7 9l5-5 5 5M4 20h16", stroke = 2f)
    val Package = icon("package", "M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9")
    val Unplug = icon("unplug", "M12 3v8M6.4 6.6a8 8 0 1 0 11.2 0")
    val Link = icon("link", "M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1")
}

/** Mono uppercase label; [heading] marks section titles for screen readers. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, style: TextStyle = T.label, heading: Boolean = false) {
    Text(text.uppercase(), style = style, modifier = if (heading) modifier.semantics { heading() } else modifier)
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier, style: TextStyle = T.title) {
    Text(text, style = style, modifier = modifier.semantics { heading() })
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** White primary button, 48 dp. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) C.Text else C.Line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.button, color = if (enabled) C.Bg else C.Faint)
    }
}

/** Outlined secondary button, 48 dp. */
@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = C.Text) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.button.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

/** Text-only button, 44 dp. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = C.Text2) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = T.small.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

enum class Chip { Filled, Outlined, Dashed }

/** Compact 36 dp button inside a 44 dp touch target (row actions, presets). */
@Composable
fun ChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: Chip = Chip.Outlined,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    description: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(44.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(36.dp)
                .then(
                    when (style) {
                        Chip.Filled -> Modifier.background(C.Text, shape)
                        Chip.Outlined -> Modifier.border(1.dp, if (icon != null) C.Outline else C.Dashed, shape)
                        Chip.Dashed -> Modifier.dashedBorder(C.Dashed, 12.dp)
                    },
                )
                .padding(start = if (icon != null) 8.dp else 14.dp, end = if (icon != null) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val fg = if (style == Chip.Filled) C.Bg else if (style == Chip.Dashed) C.Text2 else C.Text
            if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
            Text(text, style = T.small.copy(fontSize = 13.sp, fontWeight = if (style == Chip.Dashed || icon != null) FontWeight.Medium else FontWeight.SemiBold), color = fg)
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = C.Text2, enabled: Boolean = true, iconSize: Dp = 22.dp, iconModifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, iconModifier.size(iconSize), tint = tint)
    }
}

fun Modifier.dashedBorder(color: Color, radius: Dp) = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** 2 dp progress line: determinate with [fraction], sliding otherwise. */
@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction }, modifier = modifier.fillMaxWidth().height(2.dp), color = C.Text, trackColor = C.Line,
            strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth().height(2.dp), color = C.Text, trackColor = C.Line, strokeCap = StrokeCap.Round, gapSize = 0.dp)
    }
}

@Composable
fun Spinner(size: Dp = 14.dp, color: Color = C.Text) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(Modifier.size(size).rotate(angle)) {
        drawArc(color, 0f, 270f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Single-line input in the reference style: 48 dp, dark fill, subtle outline. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    enabled: Boolean = true,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = (if (mono) T.mono.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal) else T.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Normal)).copy(color = if (enabled) C.Text else C.Muted),
            cursorBrush = SolidColor(C.Text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onAny = { onIme() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(C.Surface, RoundedCornerShape(12.dp))
                        .border(1.dp, C.Outline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = T.small.copy(color = C.Faint))
                    inner()
                }
            },
        )
    }
}

enum class Tab { Car, Files, Commands }

/** The floating, rounded bottom navigation. Files and Commands need a connection. */
@Composable
fun BottomNav(selected: Tab, connected: Boolean, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(24.dp, RoundedCornerShape(32.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .height(64.dp)
            .background(C.Raised, RoundedCornerShape(32.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(32.dp))
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in Tab.entries) {
            val icon = when (tab) {
                Tab.Car -> Icons.Car
                Tab.Files -> Icons.Folder
                Tab.Commands -> Icons.Terminal
            }
            val enabled = tab == Tab.Car || connected
            val isSel = tab == selected
            Row(
                Modifier
                    .height(48.dp)
                    .then(if (isSel) Modifier else Modifier.width(56.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSel) C.Text else Color.Transparent)
                    .clickable(enabled = enabled && !isSel, role = Role.Tab) { onSelect(tab) }
                    .semantics {
                        this.selected = isSel
                        contentDescription = if (enabled) tab.name else "${tab.name}, available once connected"
                        if (!enabled) disabled()
                    }
                    .alpha(if (enabled) 1f else 0.4f)
                    .padding(if (isSel) PaddingValues(start = 16.dp, end = 20.dp) else PaddingValues()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = if (isSel) C.Bg else C.Text2)
                if (isSel) Text(tab.name, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = C.Bg)
            }
        }
    }
}

/** Status message in the reference's snack style. */
@Composable
fun Snack(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = T.small.copy(color = C.Text, fontWeight = FontWeight.Medium),
        modifier = modifier
            .fillMaxWidth()
            .background(C.Line, RoundedCornerShape(12.dp))
            .border(1.dp, C.Outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Monogram or saved launcher icon for an app. */
@Composable
fun AppTile(monogram: String, icon: androidx.compose.ui.graphics.ImageBitmap?, installed: Boolean, size: Dp = 64.dp, badge: @Composable (() -> Unit)? = null) {
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
                .then(if (installed) Modifier.background(C.Line) else Modifier.dashedBorder(C.Dashed, 14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(icon, null, Modifier.size(size * 0.72f).alpha(if (installed) 1f else 0.5f))
            } else {
                Text(monogram, style = T.bodyStrong.copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp), color = if (installed) C.Text else C.Muted)
            }
        }
        if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(0.dp)) { badge() }
    }
}

@Suppress("ModifierParameter") // color first: it is passed far more often
@Composable
fun MetaText(text: String, color: Color = C.Muted, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(text, style = T.meta, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Space reserved at the bottom of scrolling screens for the floating navigation. */
val NavReserve = 104.dp

@Composable
fun Surface(modifier: Modifier = Modifier, border: BorderStroke? = BorderStroke(1.dp, C.Outline), radius: Dp = 14.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(C.Surface)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(radius)) else Modifier),
    ) { content() }
}

@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().widthIn(max = 560.dp), contentAlignment = Alignment.TopCenter) { content() }
}
