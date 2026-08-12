package com.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val AVATAR_SIZE = 44.dp
val LARGE_AVATAR_SIZE = 96.dp

@Composable
fun Avatar(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = AVATAR_SIZE,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = CircleShape,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = initialsOf(label), style = textStyle)
        }
    }
}

fun initialsOf(label: String): String {
    val words = label.trim().split(' ', '\t').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].takeCodePoints(2)
        else -> words[0].takeCodePoints(1) + words[1].takeCodePoints(1)
    }.uppercase()
}

private fun String.takeCodePoints(count: Int): String {
    var index = 0
    var taken = 0
    while (index < length && taken < count) {
        val isSurrogatePair = this[index].isHighSurrogate() &&
            index + 1 < length &&
            this[index + 1].isLowSurrogate()
        index += if (isSurrogatePair) 2 else 1
        taken++
    }
    return substring(0, index)
}
