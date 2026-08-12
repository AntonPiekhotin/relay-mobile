package com.relay.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val GLYPH_SIZE = 24.dp
private const val STROKE_RATIO = 0.09f

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

private fun Modifier.describedAs(description: String): Modifier =
    semantics { contentDescription = description }
