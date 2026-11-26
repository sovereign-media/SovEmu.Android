package media.sovereign.sovemu.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.SystemSummary
import media.sovereign.sovemu.core.parseColor

// SovEmu.Web's theme (src/styles/global.css) and Tailwind's slate greys.
object Sov {
    val Surface = Color(0xFF0B0D12)
    val Panel = Color(0xFF151922)
    val Edge = Color(0xFF2A3142)
    val Muted = Color(0xFF8B93A7)
    val Accent = Color(0xFF4169E1)
    val Slate100 = Color(0xFFF1F5F9)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)
    val Slate950 = Color(0xFF020617)
    val Red300 = Color(0xFFFCA5A5)
    val Amber200 = Color(0xFFFDE68A)
}

/** Lucide's play, filled (`fill-current` on the web's Play buttons). */
val PlayFilled: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(
            pathData = addPathNodes(
                "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7" +
                    "A2 2 0 0 1 5 19z",
            ),
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}

private val colours = HashMap<String, Color>()

/** The system's colour, parsed once per hex value (composition reads it on every pass). */
fun SystemSummary.colour(): Color = colours.getOrPut(color) { Color(parseColor(color)) }

/** `linear-gradient(145deg, c, color-mix(c [mix]%, black))`, the web's card fill. */
fun tile(c: Color, mix: Float = 0.3f): Brush =
    Brush.linearGradient(listOf(c, lerp(Color.Black, c, mix)))

/**
 * How wide the screen is: a TV or a tablet (the side dock) or a phone (the tab bar). Whether it
 * is a TV is the surface's to know (`:common:feature-tv`), never a branch in a shared screen.
 */
data class Form(val wide: Boolean)

val LocalForm = staticCompositionLocalOf { Form(wide = true) }

/**
 * The server's background behind every screen (a `/media/backgrounds/…` path), or null for none.
 */
val LocalBackground = compositionLocalOf<String?> { null }

/** The theme, and the [Form] for [tv]: true in the TV apps, false in the phone and tablet app. */
@Composable
fun SovTheme(tv: Boolean, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val form = Form(wide = tv || config.screenWidthDp >= 600)
    // A TV says it is 960dp wide, which draws the web's layout at twice its size. Lay it out on
    // 1280dp instead: the web client at 150%, a comfortable size from the sofa.
    val density = LocalDensity.current
    val scaled = remember(density, config.screenWidthDp, tv) {
        if (!tv) {
            density
        } else {
            Density(density.density * config.screenWidthDp / 1280f, density.fontScale)
        }
    }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Sov.Accent,
                background = Sov.Surface,
                surface = Sov.Panel,
                surfaceContainer = Sov.Panel,
                onSurface = Sov.Slate100,
                outline = Sov.Edge,
            ),
        ) {
            CompositionLocalProvider(LocalForm provides form, content = content)
        }
    }
}

/** A Lucide icon, tinted. */
@Composable
fun LucideIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 20.dp,
) = Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)

/**
 * The web's focus treatment for cards: scaled up 5% with a white ring. The scale is read in the
 * draw layer ([scale] is a lambda), so its animation redraws the card without recomposing it.
 */
fun Modifier.focusRing(
    focused: Boolean,
    scale: () -> Float,
    shape: Shape,
    ring: Dp = 4.dp,
): Modifier = graphicsLayer {
    val s = scale()
    scaleX = s
    scaleY = s
}.then(if (focused) Modifier.border(ring, Color.White.copy(alpha = 0.8f), shape) else Modifier)

/**
 * A border only when [show]: `border(0.dp)` is not "no border" (it draws a hairline), and a
 * transparent one still costs a draw.
 */
fun Modifier.borderIf(show: Boolean, width: Dp, color: Color, shape: Shape): Modifier =
    if (show) border(width, color, shape) else this

/** A clickable, focusable card surface; [content] gets whether it has the focus. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "focus")
    // The focusable node keeps its real size; only what it draws is scaled. (Scaled, its bounds
    // would grow as it takes the focus, and the list would scroll to fit them and back: a bounce.)
    Box(modifier.clickable(interactionSource = source, indication = null, onClick = onClick)) {
        Box(
            Modifier.matchParentSize().focusRing(focused, { scale.value }, shape).clip(shape),
        ) { content(focused) }
    }
}

enum class Variant { Primary, Outline, Ghost, Light, Glass }

/** SovEmu.Web's Button: a rounded pill per role, lg size. Focus shows a white ring. */
@Composable
fun SovButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: Variant = Variant.Primary,
    icon: ImageVector? = null,
    filledIcon: Boolean = false,
    enabled: Boolean = true,
    large: Boolean = true,
    iconTint: Color? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val (bg, fg) = when (variant) {
        Variant.Primary -> Sov.Accent to Color.White

        Variant.Outline -> Color.Transparent to Sov.Slate200

        Variant.Ghost ->
            (if (focused) Color.White.copy(alpha = 0.1f) else Color.Transparent) to Sov.Slate400

        Variant.Light -> Color.White to Sov.Slate950

        Variant.Glass -> Color.White.copy(alpha = if (focused) 0.25f else 0.15f) to Color.White
    }
    // The focus ring sits outside the button (Tailwind's ring), so it shows on a white one too;
    // the space for it is always there, so focusing never moves anything.
    Row(
        modifier
            .borderIf(focused, 3.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(9.dp))
            .padding(3.dp)
            .height(if (large) 44.dp else 36.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .borderIf(variant == Variant.Outline, 1.dp, Sov.Edge, shape)
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (large) 20.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            LucideIcon(if (filledIcon) PlayFilled else icon, tint = iconTint ?: fg, size = 18.dp)
        }
        Text(
            label,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (large) 16.sp else 14.sp,
            maxLines = 1,
        )
    }
}

/** A capsule toolbar of chips (the web's TabStrip). */
@Composable
fun ChipStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Sov.Surface.copy(alpha = 0.9f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ChipDivider(modifier: Modifier = Modifier) = Box(
    modifier
        .padding(horizontal = 4.dp)
        .width(1.dp)
        .height(20.dp)
        .background(Color.White.copy(alpha = 0.1f)),
)

/** A chip: quiet at rest, filled when its action is in effect ([on]). */
@Composable
fun Chip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    on: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val bg = when {
        on -> Color.White.copy(alpha = 0.15f)
        focused -> Color.White.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
    val fg = if (on || focused) Color.White else Sov.Slate400
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .borderIf(focused, 2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            .background(bg)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LucideIcon(icon, tint = fg, size = 14.dp)
        Text(
            label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** A toggle pill (the filter drawer's): accent when on. */
@Composable
fun Toggle(
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(
                when {
                    on -> Sov.Accent
                    focused -> Color.White.copy(alpha = 0.1f)
                    else -> Color.Transparent
                },
            )
            .borderIf(
                focused || !on,
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f),
                CircleShape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on) LucideIcon(Lucide.Check, size = 14.dp)
        content()
    }
}

/** The system's coloured badge ("SNES"). */
@Composable
fun SystemBadge(
    short: String,
    colour: Color,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) = Text(
    short,
    color = Color.White,
    fontWeight = FontWeight.Black,
    fontSize = if (large) 14.sp else 12.sp,
    modifier = modifier
        .clip(RoundedCornerShape(if (large) 8.dp else 6.dp))
        .background(colour)
        .padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 4.dp else 2.dp),
)

/** A row's title with its icon ("Continue playing"). */
@Composable
fun SectionTitle(title: String, icon: ImageVector, modifier: Modifier = Modifier) = Row(
    modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    LucideIcon(icon, tint = Sov.Slate400)
    Text(
        title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The uppercase label over a section ("SAVES", "GENRE"). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(),
    color = Sov.Slate500,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 1.2.sp,
    modifier = modifier,
)

/**
 * A card's detail line with the game's release label (what tells it from same-titled games on its
 * system: "Translation", "Rev 1") in amber, before the rest ([first]) or after its first part.
 */
@Composable
fun withRelease(release: String?, line: String, first: Boolean): AnnotatedString =
    remember(release, line, first) {
        buildAnnotatedString {
            if (release == null) {
                append(line)
                return@buildAnnotatedString
            }
            val label = {
                withStyle(SpanStyle(color = Sov.Amber200, fontWeight = FontWeight.Medium)) {
                    append(release)
                }
            }
            if (first) {
                label()
                append(" · ")
                append(line)
            } else {
                append(line)
                append(" · ")
                label()
            }
        }
    }
