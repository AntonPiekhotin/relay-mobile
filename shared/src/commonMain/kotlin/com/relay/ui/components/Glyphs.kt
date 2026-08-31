package com.relay.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

const val DECLINE_ROTATION = 135f

@Composable
fun SearchGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Search, contentDescription, modifier, tint)

@Composable
fun BackGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Back, contentDescription, modifier, tint)

@Composable
fun SendGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Send, contentDescription, modifier, tint)

@Composable
fun AccountGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Contacts, contentDescription, modifier, tint)

@Composable
fun GroupGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Group, contentDescription, modifier, tint)

@Composable
fun PhoneGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    rotationDegrees: Float = 0f
) = Glyph(RelayIcons.Phone, contentDescription, modifier.rotate(rotationDegrees), tint)

@Composable
fun MicrophoneGlyph(
    contentDescription: String?,
    muted: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(if (muted) RelayIcons.MicOff else RelayIcons.Mic, contentDescription, modifier, tint)

@Composable
fun SpeakerGlyph(
    contentDescription: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(if (enabled) RelayIcons.Speaker else RelayIcons.SpeakerOff, contentDescription, modifier, tint)

@Composable
fun ChatGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Chats, contentDescription, modifier, tint)

@Composable
fun SettingsGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Settings, contentDescription, modifier, tint)

@Composable
fun CloseGlyph(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) = Glyph(RelayIcons.Close, contentDescription, modifier, tint)

@Composable
private fun Glyph(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier,
    tint: Color
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
    )
}
