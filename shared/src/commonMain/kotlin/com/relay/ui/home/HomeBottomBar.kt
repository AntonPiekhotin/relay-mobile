package com.relay.ui.home

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.relay.ui.components.AccountGlyph
import com.relay.ui.components.ChatGlyph
import com.relay.ui.components.PhoneGlyph
import com.relay.ui.components.SearchGlyph
import com.relay.ui.components.SettingsGlyph

enum class HomeTab { CONTACTS, CALLS, CHATS, SEARCH, SETTINGS }

fun homeTabTag(tab: HomeTab): String = "home-tab-${tab.name.lowercase()}"

private val HomeTab.label: String
    get() = when (this) {
        HomeTab.CONTACTS -> "Contacts"
        HomeTab.CALLS -> "Calls"
        HomeTab.CHATS -> "Chats"
        HomeTab.SEARCH -> "Search"
        HomeTab.SETTINGS -> "Settings"
    }

@Composable
fun HomeBottomBar(
    selected: HomeTab,
    chatsBadge: Long,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        HomeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { HomeTabIcon(tab = tab, chatsBadge = chatsBadge) },
                label = { Text(tab.label) },
                modifier = Modifier.testTag(homeTabTag(tab))
            )
        }
    }
}

@Composable
private fun HomeTabIcon(tab: HomeTab, chatsBadge: Long) {
    when (tab) {
        HomeTab.CONTACTS -> AccountGlyph(contentDescription = tab.label)
        HomeTab.CALLS -> PhoneGlyph(contentDescription = tab.label)
        HomeTab.CHATS -> ChatsIcon(badge = chatsBadge)
        HomeTab.SEARCH -> SearchGlyph(contentDescription = tab.label)
        HomeTab.SETTINGS -> SettingsGlyph(contentDescription = tab.label)
    }
}

@Composable
private fun ChatsIcon(badge: Long) {
    if (badge > 0) {
        BadgedBox(badge = { Badge { Text(badge.toString()) } }) {
            ChatGlyph(contentDescription = "Chats")
        }
    } else {
        ChatGlyph(contentDescription = "Chats")
    }
}
