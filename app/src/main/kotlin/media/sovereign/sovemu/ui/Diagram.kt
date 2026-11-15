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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import media.sovereign.sovemu.core.Fit
import media.sovereign.sovemu.core.PadLayout
import media.sovereign.sovemu.core.SystemControls
import media.sovereign.sovemu.core.bounds

/** What the controllers screen draws: the game's system (null if it could not load) and the controllers' pads. */
@Immutable
data class Controls(val system: SystemControls? = null, val layouts: List<PadLayout> = emptyList())

private val BodyFill = Color(0xFF2B3242)
private val RecessFill = Color(0xFF10131A)
private val KeyFill = Color(0xFF4A5368)
private val LitFill = Color(0xFF4169E1)

/**
 * A pad from the server's layout data (docs/CONTROLLERS.md, "Systems and diagrams"): the body's
 * rounded rectangles (tone 1 darker), then each key with its label, the [lit] keys highlighted.
 * The shapes' bounding box is scaled to fit, centred.
 */
@Composable
fun PadDiagram(layout: PadLayout, lit: Set<String>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Column(modifier) {
        Text(layout.name, color = Color(0xFF8B93A7), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            // Cropped to the shapes (+3 units), as the web client draws it.
            val box = layout.bounds()
            val fit = Fit.of(box.w, box.h, size.width, size.height)
            val k = fit.scale
            fun at(x: Double, y: Double) = Offset(fit.dx + (x - box.x).toFloat() * k, fit.dy + (y - box.y).toFloat() * k)
            fun size(w: Double, h: Double) = Size(w.toFloat() * k, h.toFloat() * k)
            for (s in layout.body) {
                drawRoundRect(
                    if (s.tone == 1) RecessFill else BodyFill, at(s.x, s.y), size(s.w, s.h), CornerRadius(s.r.toFloat() * k),
                )
            }
            for (key in layout.keys) {
                val on = key.key in lit
                val corner = CornerRadius(key.r.toFloat() * k)
                drawRoundRect(if (on) LitFill else KeyFill, at(key.x, key.y), size(key.w, key.h), corner)
                if (on) drawRoundRect(Color.White, at(key.x, key.y), size(key.w, key.h), corner, style = Stroke(1.5.dp.toPx()))
                if (key.label.isEmpty()) continue
                // As large as fits the key: by its height, and by its width for longer labels.
                val px = minOf(key.h.toFloat() * 0.62f, key.w.toFloat() * 1.5f / maxOf(1, key.label.length)) * k
                val text = measurer.measure(
                    key.label,
                    TextStyle(color = Color.White, fontSize = px.toSp(), fontWeight = FontWeight.Bold),
                    softWrap = false,
                )
                val centre = at(key.x + key.w / 2, key.y + key.h / 2)
                drawText(text, topLeft = Offset(centre.x - text.size.width / 2f, centre.y - text.size.height / 2f))
            }
        }
    }
}

