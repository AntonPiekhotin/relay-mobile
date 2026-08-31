package com.relay.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private const val ICON_VIEWPORT = 24f
private const val ICON_STROKE_WIDTH = 1.8f
private val ICON_SIZE = 24.dp

private const val MIC_BODY = "M9 5.6V10.6A3 3 0 0 0 15 10.6V5.6A3 3 0 0 0 9 5.6Z"
private const val MIC_CRADLE = "M5.5 10.8a6.5 6.5 0 0 0 13 0"
private const val MIC_STEM = "M12 17.3V21"
private const val MIC_BASE = "M8.6 21h6.8"
private const val SLASH = "M3.6 3.6 20.4 20.4"
private const val SPEAKER_CONE = "M4.8 9.1H7.7L11.5 5.3V18.7L7.7 14.9H4.8Z"
private const val SPEAKER_NEAR_WAVE = "M14.6 9.6a3.4 3.4 0 0 1 0 4.8"
private const val SPEAKER_FAR_WAVE = "M17.2 7a6.8 6.8 0 0 1 0 10"

object RelayIcons {

    val Chats: ImageVector = strokeIcon(
        "chats",
        "M20.6 11.6c0 4.4-3.9 8-8.6 8a9.6 9.6 0 0 1-3.5-.7l-5.1 1.6 1.6-4.5a7.7 7.7 0 0 1-1.6-4.7" +
            "c0-4.4 3.9-8 8.6-8s8.6 3.6 8.6 8Z"
    )

    val Group: ImageVector = strokeIcon(
        "group",
        "M5.7 8.2a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0",
        "M2.8 19.8a6.4 6.4 0 0 1 12.8 0",
        "M16.4 5.2a3.5 3.5 0 0 1 0 6",
        "M17.8 14.2a6.4 6.4 0 0 1 3.4 5.6"
    )

    val Contacts: ImageVector = strokeIcon(
        "contacts",
        "M8.4 8a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0-7.2 0",
        "M4.9 20.2a7.2 7.2 0 0 1 14.2 0"
    )

    val Phone: ImageVector = strokeIcon(
        "phone",
        "M8.3 3.4 4.9 3.1A1.8 1.8 0 0 0 3 4.9C2.6 13 11 21.4 19.1 21a1.8 1.8 0 0 0 1.8-1.9l-.3-3.4" +
            "-4.4-1.2-1.8 2.2a14.2 14.2 0 0 1-5.1-5.1l2.2-1.8L8.3 3.4Z"
    )

    val Mic: ImageVector = strokeIcon("mic", MIC_BODY, MIC_CRADLE, MIC_STEM, MIC_BASE)

    val MicOff: ImageVector = strokeIcon("mic-off", MIC_BODY, MIC_CRADLE, MIC_STEM, MIC_BASE, SLASH)

    val Speaker: ImageVector = strokeIcon(
        "speaker",
        SPEAKER_CONE,
        SPEAKER_NEAR_WAVE,
        SPEAKER_FAR_WAVE
    )

    val SpeakerOff: ImageVector = strokeIcon("speaker-off", SPEAKER_CONE)

    val Search: ImageVector = strokeIcon(
        "search",
        "M4.2 10.8a6.6 6.6 0 1 0 13.2 0a6.6 6.6 0 1 0-13.2 0",
        "M15.7 15.7 20.6 20.6"
    )

    val Send: ImageVector = strokeIcon(
        "send",
        "M20.8 3.2 10.6 13.4",
        "M20.8 3.2 14.4 20.8a.6.6 0 0 1-1.13.03l-2.67-7.43-7.43-2.67a.6.6 0 0 1 .03-1.13L20.8 3.2Z"
    )

    val Close: ImageVector = strokeIcon(
        "close",
        "M5.5 5.5 18.5 18.5",
        "M18.5 5.5 5.5 18.5"
    )

    val Back: ImageVector = strokeIcon(
        "back",
        "M20 12H4.4",
        "M10.6 5.4 4 12 10.6 18.6"
    )

    val Settings: ImageVector = strokeIcon(
        "settings",
        "M6.1 12a5.9 5.9 0 1 0 11.8 0a5.9 5.9 0 1 0-11.8 0",
        "M9.7 12a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0-4.6 0",
        "M17.9 12H20.8",
        "M16.17 16.17 18.22 18.22",
        "M12 17.9V20.8",
        "M7.83 16.17 5.78 18.22",
        "M6.1 12H3.2",
        "M7.83 7.83 5.78 5.78",
        "M12 6.1V3.2",
        "M16.17 7.83 18.22 5.78"
    )
}

private fun strokeIcon(name: String, vararg pathData: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = ICON_SIZE,
        defaultHeight = ICON_SIZE,
        viewportWidth = ICON_VIEWPORT,
        viewportHeight = ICON_VIEWPORT
    )
    pathData.forEach { data ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = ICON_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }
    return builder.build()
}
