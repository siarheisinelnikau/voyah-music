package app.voyahmusic.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voyahmusic.shared.ui.MusicGlyphs.Icon
import kotlin.math.roundToInt

internal val Background = Color(0xFF0F141C)
internal val Panel = Color(0xFF17202D)
internal val Field = Color(0xFF192230)
internal val Ink = Color(0xFFEDF2FA)
internal val Muted = Color(0xFFADBACE)
internal val ErrorInk = Color(0xFFFFBAAA)

internal data class MusicColors(val accent: Color, val onAccent: Color, val selected: Color, val selectedEdge: Color, val edge: Color)

private val Graphite = MusicColors(Color(0xFFB5CFFF), Color(0xFF182842), Color(0xFF253752), Color(0xFF506E9C), Color(0xFF2D3A4E))
private val Amber = MusicColors(Color(0xFFF5CF71), Color(0xFF362400), Color(0xFF4F3913), Color(0xFF785E36), Color(0xFF3F3B38))
private val LocalMusicColors = staticCompositionLocalOf { Graphite }

internal val musicColors: MusicColors
    @Composable get() = LocalMusicColors.current

@Composable
internal fun MusicTheme(amber: Boolean, content: @Composable () -> Unit) {
    val colors = if (amber) Amber else Graphite
    CompositionLocalProvider(LocalMusicColors provides colors) {
        MaterialTheme(darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent,
            background = Background, surface = Panel, onSurface = Ink, onSurfaceVariant = Muted,
            surfaceContainerHigh = Panel, outline = colors.edge), content = content)
    }
}

@Composable
internal fun MusicText(
    text: String, size: Int, modifier: Modifier = Modifier, medium: Boolean = false,
    color: Color = Ink, align: TextAlign = TextAlign.Start, lines: Int = Int.MAX_VALUE,
    mono: Boolean = false, lineHeight: Int? = null,
) {
    Text(text, modifier, style = TextStyle(
        color = color, fontSize = size.sp, fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif, textAlign = align,
        lineHeight = lineHeight?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
    ), maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun Modifier.musicPanel(fill: Color, edge: Color, radius: Int): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val density = LocalDensity.current
    val line = with(density) { (1.dp.roundToPx() - .01f).toDp() }
    return clip(shape).background(fill).then(if (edge == Color.Transparent) Modifier else Modifier.border(line, edge, shape))
}

@Composable
internal fun MusicIcon(icon: Icon, side: Int, color: Color, modifier: Modifier = Modifier) {
    val drawing = remember(icon, color) { MusicGlyphs.Glyph(icon, color.toArgb()) }
    Canvas(modifier.size(side.dp)) {
        drawIntoCanvas { canvas ->
            drawing.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawing.draw(canvas.nativeCanvas)
        }
    }
}

@Composable
internal fun MusicIconButton(
    icon: Icon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false, active: Boolean = false, glyphSize: Int = if (primary) 26 else 22,
) {
    val colors = musicColors
    val fill = if (primary) colors.accent else if (active) colors.selected else Field
    val edge = if (primary) Color.Transparent else if (active) colors.selectedEdge else colors.edge
    val tint = if (primary) colors.onAccent else if (active) colors.accent else Ink
    Box(modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .alpha(if (enabled) 1f else 0.45f).musicPanel(fill, edge, 14)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = ripple(), onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        MusicIcon(icon, glyphSize, tint)
    }
}

@Composable
internal fun MusicButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
    enabled: Boolean = true, height: Dp = 48.dp,
) {
    val colors = musicColors
    Box(modifier.heightIn(min = height).alpha(if (enabled) 1f else 0.45f)
        .musicPanel(if (primary) colors.accent else Field, if (primary) Color.Transparent else colors.edge, 13)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = null, indication = ripple(), onClick = onClick)
        .padding(horizontal = 13.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        MusicText(label, 15, medium = true, color = if (primary) colors.onAccent else Ink, align = TextAlign.Center)
    }
}

@Composable
internal fun MusicInput(
    value: String, change: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, height: Int = 48, search: Boolean = false, keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = musicColors
    val size = if (search) 16 else 17
    BasicTextField(value, change, modifier.fillMaxWidth(), singleLine = true, enabled = enabled, keyboardOptions = keyboard,
        cursorBrush = SolidColor(colors.accent), textStyle = TextStyle(color = Ink, fontSize = size.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)), decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = height.dp).musicPanel(Field, colors.edge, 13)
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (search) {
                    MusicIcon(Icon.SEARCH, 19, colors.accent)
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) MusicText(hint, size, color = Muted, lines = 1)
                    input()
                }
            }
        })
}

@Composable
internal fun MusicHeading(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            MusicIconButton(Icon.BACK, androidx.compose.ui.res.stringResource(app.voyahmusic.shared.R.string.back),
                back, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
        }
        MusicText(title, 25, Modifier.weight(1f), medium = true, lines = 2)
        actions()
    }
}

@Composable
internal fun MusicPage(content: @Composable ColumnScope.(Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 12.dp)) {
            content(wide)
        }
    }
}

@Composable
internal fun SettingsAction(update: Boolean, open: () -> Unit) {
    if (update) {
        MusicButton(androidx.compose.ui.res.stringResource(app.voyahmusic.shared.R.string.update_available_short), open, primary = true)
        Spacer(Modifier.width(8.dp))
    }
    MusicIconButton(Icon.SETTINGS, androidx.compose.ui.res.stringResource(app.voyahmusic.shared.R.string.settings), open, Modifier.size(48.dp))
}

@Composable
internal fun OldSwitch(checked: Boolean, change: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val accent = musicColors.accent
    val thumb by animateFloatAsState(if (checked) 1f else 0f, tween(250), label = "Switch thumb")
    Row(modifier.heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        MusicText(label, 14, Modifier.weight(1f))
        Canvas(Modifier.width(42.dp).height(24.dp)) {
            val x = (9.dp + 11.dp * thumb).toPx()
            val start = androidx.compose.ui.geometry.Offset(1.dp.toPx(), 5.dp.toPx())
            drawRoundRect(if (checked) accent.copy(alpha = 0.40f) else Color(0xFF616875), topLeft = start,
                size = androidx.compose.ui.geometry.Size(29.dp.toPx(), 14.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
            drawCircle(if (checked) accent else Color(0xFFBDBDBD), 10.dp.toPx(),
                androidx.compose.ui.geometry.Offset(x, 12.dp.toPx()))
        }
    }
}
