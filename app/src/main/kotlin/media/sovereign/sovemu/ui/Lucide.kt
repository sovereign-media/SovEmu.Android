package media.sovereign.sovemu.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated from lucide-react (ISC licence, https://lucide.dev): the icons SovEmu.Web uses,
// stroked on a 24×24 grid as Lucide draws them. Tinted by Icon(), like any vector.
object Lucide {
    private fun icon(vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (d in paths) {
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    val House: ImageVector by lazy {
        icon(
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )
    }

    val Gamepad2: ImageVector by lazy {
        icon(
            "M6 11L10 11",
            "M8 9L8 13",
            "M15 12L15.01 12",
            "M18 10L18.01 10",
            "M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.151A4 4 0 0 0 17.32 5z",
        )
    }

    val History: ImageVector by lazy {
        icon(
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
            "M12 7v5l4 2",
        )
    }

    val Sparkles: ImageVector by lazy {
        icon(
            "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
            "M20 2v4",
            "M22 4h-4",
            "M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        )
    }

    val Play: ImageVector by lazy {
        icon(
            "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
        )
    }

    val LayoutGrid: ImageVector by lazy {
        icon(
            "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1z",
            "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1z",
            "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1z",
            "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-5a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1z",
        )
    }

    val ChevronLeft: ImageVector by lazy {
        icon(
            "m15 18-6-6 6-6",
        )
    }

    val ChevronRight: ImageVector by lazy {
        icon(
            "m9 18 6-6-6-6",
        )
    }

    val ArrowDownAZ: ImageVector by lazy {
        icon(
            "m3 16 4 4 4-4",
            "M7 20V4",
            "M20 8h-5",
            "M15 10V6.5a2.5 2.5 0 0 1 5 0V10",
            "M15 14h5l-5 6h5",
        )
    }

    val SlidersHorizontal: ImageVector by lazy {
        icon(
            "M10 5H3",
            "M12 19H3",
            "M14 3v4",
            "M16 17v4",
            "M21 12h-9",
            "M21 19h-5",
            "M21 5h-7",
            "M8 10v4",
            "M8 12H3",
        )
    }

    val BookA: ImageVector by lazy {
        icon(
            "M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20",
            "m8 13 4-7 4 7",
            "M9.1 11h5.7",
        )
    }

    val Users: ImageVector by lazy {
        icon(
            "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
            "M16 3.128a4 4 0 0 1 0 7.744",
            "M22 21v-2a4 4 0 0 0-3-3.87",
            "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
        )
    }

    val CircleCheck: ImageVector by lazy {
        icon(
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "m16 9-5.5 5.5L8 12",
        )
    }

    val Microchip: ImageVector by lazy {
        icon(
            "M10 12h4",
            "M10 17h4",
            "M10 7h4",
            "M18 12h2",
            "M18 18h2",
            "M18 6h2",
            "M4 12h2",
            "M4 18h2",
            "M4 6h2",
            "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2z",
        )
    }

    val X: ImageVector by lazy {
        icon(
            "M18 6 6 18",
            "m6 6 12 12",
        )
    }

    val Search: ImageVector by lazy {
        icon(
            "m21 21-4.34-4.34",
            "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0",
        )
    }

    val Check: ImageVector by lazy {
        icon(
            "M20 6 9 17l-5-5",
        )
    }

    val CalendarDays: ImageVector by lazy {
        icon(
            "M8 2v3",
            "M16 2v3",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
            "M3 9h18",
            "M8 13h.01",
            "M12 13h.01",
            "M16 13h.01",
            "M8 17h.01",
            "M12 17h.01",
            "M16 17h.01",
        )
    }

    val Building2: ImageVector by lazy {
        icon(
            "M10 12h4",
            "M10 8h4",
            "M14 21v-3a2 2 0 0 0-4 0v3",
            "M6 10H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2",
            "M6 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16",
        )
    }

    val Tag: ImageVector by lazy {
        icon(
            "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z",
            "M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0",
        )
    }

    val RotateCcw: ImageVector by lazy {
        icon(
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
        )
    }

    val LogOut: ImageVector by lazy {
        icon(
            "m16 17 5-5-5-5",
            "M21 12H9",
            "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
        )
    }

    val LogIn: ImageVector by lazy {
        icon(
            "m10 17 5-5-5-5",
            "M15 12H3",
            "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4",
        )
    }

    val CircleAlert: ImageVector by lazy {
        icon(
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "M12 8L12 12",
            "M12 16L12.01 16",
        )
    }

    val Move: ImageVector by lazy {
        icon(
            "M12 2v20",
            "m15 19-3 3-3-3",
            "m19 9 3 3-3 3",
            "M2 12h20",
            "m5 9-3 3 3 3",
            "m9 5 3-3 3 3",
        )
    }

    val Menu: ImageVector by lazy {
        icon(
            "M4 5h16",
            "M4 12h16",
            "M4 19h16",
        )
    }

    val SquareMinus: ImageVector by lazy {
        icon(
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
            "M8 12h8",
        )
    }

    val Server: ImageVector by lazy {
        icon(
            "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z",
            "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z",
            "M6 6L6.01 6",
            "M6 18L6.01 18",
        )
    }
}
