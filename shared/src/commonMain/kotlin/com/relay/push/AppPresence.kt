package com.relay.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPresence {
    private val mutableForeground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = mutableForeground.asStateFlow()

    private val mutableOpenDialogId = MutableStateFlow<String?>(null)
    val openDialogId: StateFlow<String?> = mutableOpenDialogId.asStateFlow()

    fun onForeground() {
        mutableForeground.value = true
    }

    fun onBackground() {
        mutableForeground.value = false
    }

    fun onDialogOpened(dialogId: String) {
        mutableOpenDialogId.value = dialogId
    }

    fun onDialogClosed(dialogId: String) {
        if (mutableOpenDialogId.value == dialogId) mutableOpenDialogId.value = null
    }

    fun isShowing(dialogId: String): Boolean =
        mutableForeground.value && mutableOpenDialogId.value == dialogId
}
