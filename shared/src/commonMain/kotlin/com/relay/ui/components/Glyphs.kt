package com.relay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GLYPH_SIZE = 24.dp
private const val STROKE_RATIO = 0.09f
private const val HANDSET_TILT = -35f
const val DECLINE_ROTATION = 135f

@Composable
fun SearchGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        val radius = size.minDimension * 0.27f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color = tint, radius = radius, center = center, style = Stroke(stroke))
        drawLine(
            color = tint,
            start = Offset(center.x + radius * 0.75f, center.y + radius * 0.75f),
            end = Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun BackGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        val tip = Offset(size.width * 0.22f, size.height * 0.5f)
        drawLine(
            color = tint,
            start = tip,
            end = Offset(size.width * 0.82f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(size.width * 0.48f, size.height * 0.24f)
            lineTo(tip.x, tip.y)
            lineTo(size.width * 0.48f, size.height * 0.76f)
        }
        drawPath(
            path = head,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun SendGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        val tip = Offset(size.width * 0.5f, size.height * 0.24f)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.78f),
            end = tip,
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val head = Path().apply {
            moveTo(size.width * 0.26f, size.height * 0.48f)
            lineTo(tip.x, tip.y)
            lineTo(size.width * 0.74f, size.height * 0.48f)
        }
        drawPath(
            path = head,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun AccountGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.17f,
            center = Offset(size.width * 0.5f, size.height * 0.33f),
            style = Stroke(stroke)
        )
        drawArc(
            color = tint,
            startAngle = 190f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.58f),
            size = Size(size.width * 0.6f, size.height * 0.62f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun PhoneGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    rotationDegrees: Float = 0f
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        rotate(degrees = HANDSET_TILT + rotationDegrees) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.34f, size.height * 0.10f),
                size = Size(size.width * 0.32f, size.height * 0.24f),
                cornerRadius = CornerRadius(size.width * 0.10f),
                style = Stroke(stroke)
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.34f, size.height * 0.66f),
                size = Size(size.width * 0.32f, size.height * 0.24f),
                cornerRadius = CornerRadius(size.width * 0.10f),
                style = Stroke(stroke)
            )
            drawLine(
                color = tint,
                start = Offset(size.width * 0.5f, size.height * 0.34f),
                end = Offset(size.width * 0.5f, size.height * 0.66f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun MicrophoneGlyph(
    contentDescription: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.38f, size.height * 0.16f),
            size = Size(size.width * 0.24f, size.height * 0.42f),
            cornerRadius = CornerRadius(size.width * 0.12f),
            style = Stroke(stroke)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.26f, size.height * 0.40f),
            size = Size(size.width * 0.48f, size.height * 0.34f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.74f),
            end = Offset(size.width * 0.5f, size.height * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        if (muted) {
            drawLine(
                color = tint,
                start = Offset(size.width * 0.20f, size.height * 0.16f),
                end = Offset(size.width * 0.80f, size.height * 0.84f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun SpeakerGlyph(
    contentDescription: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        val cone = Path().apply {
            moveTo(size.width * 0.20f, size.height * 0.38f)
            lineTo(size.width * 0.32f, size.height * 0.38f)
            lineTo(size.width * 0.48f, size.height * 0.22f)
            lineTo(size.width * 0.48f, size.height * 0.78f)
            lineTo(size.width * 0.32f, size.height * 0.62f)
            lineTo(size.width * 0.20f, size.height * 0.62f)
            close()
        }
        drawPath(
            path = cone,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (enabled) {
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(size.width * 0.40f, size.height * 0.30f),
                size = Size(size.width * 0.32f, size.height * 0.40f),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(size.width * 0.36f, size.height * 0.18f),
                size = Size(size.width * 0.48f, size.height * 0.64f),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun ChatGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.14f, size.height * 0.18f),
            size = Size(size.width * 0.72f, size.height * 0.5f),
            cornerRadius = CornerRadius(size.width * 0.14f),
            style = Stroke(stroke)
        )
        val tail = Path().apply {
            moveTo(size.width * 0.3f, size.height * 0.68f)
            lineTo(size.width * 0.26f, size.height * 0.86f)
            lineTo(size.width * 0.46f, size.height * 0.68f)
        }
        drawPath(
            path = tail,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private const val GEAR_TOOTH_COUNT = 8
private const val FULL_TURN_DEGREES = 360f

@Composable
fun SettingsGlyph(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(GLYPH_SIZE).describedAs(contentDescription)) {
        val stroke = size.minDimension * STROKE_RATIO
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.13f,
            center = center,
            style = Stroke(stroke)
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.27f,
            center = center,
            style = Stroke(stroke)
        )
        repeat(GEAR_TOOTH_COUNT) { index ->
            val angle = index * (FULL_TURN_DEGREES / GEAR_TOOTH_COUNT) * (PI.toFloat() / 180f)
            val direction = Offset(cos(angle), sin(angle))
            drawLine(
                color = tint,
                start = center + direction * (size.minDimension * 0.27f),
                end = center + direction * (size.minDimension * 0.38f),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun Modifier.describedAs(description: String): Modifier =
    semantics { contentDescription = description }
