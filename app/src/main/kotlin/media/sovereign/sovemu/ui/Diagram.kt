package media.sovereign.sovemu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.Fit
import media.sovereign.sovemu.core.PadKey
import media.sovereign.sovemu.core.PadLayout
import media.sovereign.sovemu.core.SystemControls
import media.sovereign.sovemu.core.bounds

/**
 * What the controllers screen draws: the game's system (null if it could not load) and the
 * controllers' pads.
 */
@Immutable
data class Controls(val system: SystemControls? = null, val layouts: List<PadLayout> = emptyList())

private val BodyTop = Color(0xFF3A4357)
private val BodyBottom = Color(0xFF222836)
private val RecessFill = Color(0xFF12151C)
private val KeyTop = Color(0xFF5A647B)
private val KeyBottom = Color(0xFF3A4255)
private val StickCap = Color(0xFF2A303D)
private val LitFill = Color(0xFF4169E1)

/** The four D-pad keys, on a system's pad (RetroPad names) or a controller's (input names). */
private val DPAD = setOf("UP", "DOWN", "LEFT", "RIGHT", "up", "down", "left", "right")
private val STICKS = setOf("L3", "R3", "l3", "r3")

/** Face-button label colours by family, as the controllers print them. */
private fun faceColour(family: String, key: String): Color? = when (family) {
    "xbox", "generic" -> when (key) {
        "south" -> Color(0xFF4ADE80)
        "east" -> Color(0xFFF87171)
        "west" -> Color(0xFF60A5FA)
        "north" -> Color(0xFFFACC15)
        else -> null
    }

    "playstation" -> when (key) {
        "south" -> Color(0xFF93C5FD)
        "east" -> Color(0xFFFCA5A5)
        "west" -> Color(0xFFF9A8D4)
        "north" -> Color(0xFF86EFAC)
        else -> null
    }

    else -> null
}

/**
 * A pad from the server's layout data (docs/CONTROLLERS.md, "Systems and diagrams"), drawn to
 * look moulded rather than flat: the body's shapes merged into one silhouette with a shadow,
 * shading and an edge; recesses sunk into it; the D-pad as one cross; sticks as capped domes;
 * face buttons in the family's colours. The [lit] keys are highlighted. The shapes' bounding box
 * is scaled to fit, centred.
 */
@Composable
fun PadDiagram(layout: PadLayout, lit: Set<String>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        Text(
            layout.name,
            color = Color(0xFF8B93A7),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            // Cropped to the shapes (+3 units), as the web client draws it.
            val box = layout.bounds()
            val fit = Fit.of(box.w, box.h, size.width, size.height)
            val k = fit.scale
            fun rect(x: Double, y: Double, w: Double, h: Double) = Rect(
                Offset(fit.dx + (x - box.x).toFloat() * k, fit.dy + (y - box.y).toFloat() * k),
                Size(w.toFloat() * k, h.toFloat() * k),
            )

            // The body: one silhouette, shadowed, shaded top to bottom, with a faint edge.
            val silhouette = layout.body.filter { it.tone == 0 }.fold(Path()) { acc, s ->
                val piece = Path().apply {
                    addRoundRect(
                        RoundRect(rect(s.x, s.y, s.w, s.h), CornerRadius(s.r.toFloat() * k)),
                    )
                }
                Path().apply { op(acc, piece, PathOperation.Union) }
            }
            val bounds = silhouette.getBounds()
            translate(0f, 2.5.dp.toPx()) { drawPath(silhouette, Color.Black.copy(alpha = 0.35f)) }
            drawPath(
                silhouette,
                Brush.verticalGradient(listOf(BodyTop, BodyBottom), bounds.top, bounds.bottom),
            )
            drawPath(silhouette, Color.White.copy(alpha = 0.08f), style = Stroke(1.dp.toPx()))
            for (s in layout.body.filter { it.tone == 1 }) {
                val r = rect(s.x, s.y, s.w, s.h)
                drawRoundRect(RecessFill, r.topLeft, r.size, CornerRadius(s.r.toFloat() * k))
                drawRoundRect(
                    Color.Black.copy(alpha = 0.4f),
                    r.topLeft,
                    r.size,
                    CornerRadius(s.r.toFloat() * k),
                    style = Stroke(1.dp.toPx()),
                )
            }

            // The D-pad as one cross under its arms (its keys are four separate squares).
            val dpad = layout.keys.filter { it.key in DPAD }
            if (dpad.size == 4) {
                val up = dpad.first { it.key.equals("up", true) }
                val down = dpad.first { it.key.equals("down", true) }
                val left = dpad.first { it.key.equals("left", true) }
                val right = dpad.first { it.key.equals("right", true) }
                val corner = CornerRadius(1.5f * k)
                val vertical = rect(up.x, up.y, up.w, down.y + down.h - up.y)
                val horizontal = rect(left.x, left.y, right.x + right.w - left.x, left.h)
                for (arm in listOf(vertical, horizontal)) {
                    drawRoundRect(
                        Brush.verticalGradient(
                            listOf(KeyTop, KeyBottom),
                            vertical.top,
                            vertical.bottom,
                        ),
                        arm.topLeft,
                        arm.size,
                        corner,
                    )
                }
            }

            for (key in layout.keys) {
                val on = key.key in lit
                val r = rect(key.x, key.y, key.w, key.h)
                val corner = CornerRadius(key.r.toFloat() * k)
                when {
                    key.key in DPAD -> if (on) drawRoundRect(LitFill, r.topLeft, r.size, corner)

                    key.key in STICKS -> drawStick(r, on)

                    else -> {
                        drawRoundRect(
                            if (on) {
                                Brush.verticalGradient(
                                    listOf(LitFill, LitFill),
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(KeyTop, KeyBottom),
                                    r.top,
                                    r.bottom,
                                )
                            },
                            r.topLeft,
                            r.size,
                            corner,
                        )
                        drawRoundRect(
                            Color.White.copy(alpha = 0.12f),
                            r.topLeft,
                            r.size,
                            corner,
                            style = Stroke(0.75.dp.toPx()),
                        )
                    }
                }
                if (on) {
                    drawRoundRect(
                        Color.White,
                        r.topLeft,
                        r.size,
                        corner,
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                drawLabel(
                    measurer,
                    key,
                    r,
                    k,
                    if (on) {
                        Color.White
                    } else {
                        faceColour(layout.id, key.key)
                            ?: Color.White
                    },
                )
            }
        }
    }
}

/** A stick: a dark cap with a lighter, concave top. */
private fun DrawScope.drawStick(r: Rect, on: Boolean) {
    val c = r.center
    val radius = r.width / 2f
    drawCircle(if (on) LitFill else StickCap, radius, c)
    drawCircle(
        Brush.radialGradient(
            listOf(if (on) LitFill else KeyBottom, if (on) LitFill.copy(alpha = 0.8f) else KeyTop),
            center = Offset(c.x, c.y + radius * 0.2f),
            radius = radius * 0.7f,
        ),
        radius * 0.7f,
        c,
    )
    drawCircle(Color.White.copy(alpha = 0.1f), radius, c, style = Stroke(0.75.dp.toPx()))
}

/** The key's label, as large as fits: by its height, and by its width for longer labels. */
private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    key: PadKey,
    r: Rect,
    k: Float,
    colour: Color,
) {
    if (key.label.isEmpty()) return
    val px = minOf(key.h.toFloat() * 0.62f, key.w.toFloat() * 1.5f / maxOf(1, key.label.length)) * k
    val text = measurer.measure(
        key.label,
        TextStyle(color = colour, fontSize = px.toSp(), fontWeight = FontWeight.Bold),
        softWrap = false,
    )
    drawText(
        text,
        topLeft = Offset(
            r.center.x - text.size.width / 2f,
            r.center.y - text.size.height / 2f,
        ),
    )
}
