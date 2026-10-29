package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Slice(val value: Float, val color: Color)

/** Ring chart; [center] is drawn in the hole. Small gaps keep adjacent slices apart. */
@Composable
fun DonutChart(slices: List<Slice>, modifier: Modifier = Modifier, thickness: Dp = 22.dp, center: @Composable BoxScope.() -> Unit = {}) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = thickness.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            val total = slices.sumOf { it.value.toDouble() }.toFloat()
            if (total <= 0f) return@Canvas
            val gap = if (slices.size > 1) 2f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = s.value / total * 360f
                if (sweep > gap) drawArc(s.color, start + gap / 2, sweep - gap, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

/** Thin horizontal share bar (0..1). */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color, CircleShape))
    }
}

data class BarGroup(val label: String, val values: List<Float>)

/** Grouped vertical bars, one group per label, one bar per entry of [colors]. */
@Composable
fun GroupedBarChart(groups: List<BarGroup>, colors: List<Color>, modifier: Modifier = Modifier, chartHeight: Dp = 140.dp) {
    val max = groups.flatMap { it.values }.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
            val groupWidth = size.width / groups.size.coerceAtLeast(1)
            val barWidth = (groupWidth * 0.7f) / colors.size
            val radius = CornerRadius(barWidth / 3, barWidth / 3)
            groups.forEachIndexed { g, group ->
                val left = g * groupWidth + groupWidth * 0.15f
                group.values.forEachIndexed { i, v ->
                    val h = v / max * size.height
                    if (h > 0f) drawRoundRect(colors[i], Offset(left + i * barWidth + 1f, size.height - h), Size(barWidth - 2f, h), radius)
                }
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            groups.forEach {
                Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
