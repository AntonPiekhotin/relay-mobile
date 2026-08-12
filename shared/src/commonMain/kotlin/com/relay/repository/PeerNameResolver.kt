package com.relay.repository

import com.relay.auth.AuthState
import com.relay.auth.SessionManager
import com.relay.db.MessageStore
import com.relay.model.UnnamedDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PeerNameResolver(
    private val store: MessageStore,
    private val users: UserRepository,
    private val session: SessionManager,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private val resolving = mutableSetOf<String>()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            (session.state.value as? AuthState.LoggedIn)?.userId?.let { store.backfillPeers(it) }
            store.observeUnnamedDialogs().collect { rows -> rows.forEach { name(it) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        resolving.clear()
    }

    private suspend fun name(dialog: UnnamedDialog) {
        if (!resolving.add(dialog.peerId)) return
        try {
            val result = users.lookup(dialog.peerId)
            if (result is UserResult.Success) {
                store.setDialogTitle(dialog.dialogId, result.value.displayName)
            }
        } finally {
            resolving -= dialog.peerId
        }
    }
}
