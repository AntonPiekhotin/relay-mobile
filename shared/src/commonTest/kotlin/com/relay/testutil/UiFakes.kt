package com.relay.testutil

import com.relay.model.Contact
import com.relay.model.Dialog
import com.relay.model.DialogSummary
import com.relay.model.DialogSyncState
import com.relay.model.Message
import com.relay.model.SearchPage
import com.relay.model.UserProfile
import com.relay.model.UserSearchResult
import com.relay.model.UserSummary
import com.relay.repository.ConnectionPhase
import com.relay.repository.ConnectionStatus
import com.relay.repository.MessageRepository
import com.relay.repository.OpenDialogResult
import com.relay.repository.UserRepository
import com.relay.repository.UserResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeConnectionStatus(initial: ConnectionPhase = ConnectionPhase.LIVE) : ConnectionStatus {
    private val mutablePhase = MutableStateFlow(initial)
    override val phase: Flow<ConnectionPhase> = mutablePhase.asStateFlow()

    fun emit(next: ConnectionPhase) {
        mutablePhase.value = next
    }
}

class FakeUserRepository : UserRepository {
    private val stored = MutableStateFlow<List<Contact>>(emptyList())

    var profileHandler: suspend () -> UserResult<UserProfile> =
        { UserResult.Success(UserProfile(userSummary("me"), createdAtMillis = null)) }
    var lookupHandler: suspend (String) -> UserResult<UserSummary> =
        { id -> UserResult.Success(userSummary(id)) }

    val lookedUp = mutableListOf<String>()

    var searchHandler: suspend (String, Int) -> UserResult<SearchPage> =
        { _, _ -> UserResult.Success(SearchPage(emptyList(), page = 0, hasNext = false)) }
    var refreshHandler: suspend () -> UserResult<Unit> = { UserResult.Success(Unit) }
    var addHandler: suspend (UserSummary) -> UserResult<Unit> = { UserResult.Success(Unit) }
    var removeHandler: suspend (String) -> UserResult<Unit> = { UserResult.Success(Unit) }

    var searchCalls = 0
    var searchQueries = mutableListOf<String>()
    var refreshCalls = 0

    override fun observeContacts(): Flow<List<Contact>> = stored.asStateFlow()

    override suspend fun refreshContacts(): UserResult<Unit> {
        refreshCalls++
        return refreshHandler()
    }

    override suspend fun profile(): UserResult<UserProfile> = profileHandler()

    override suspend fun lookup(userId: String): UserResult<UserSummary> {
        lookedUp += userId
        return lookupHandler(userId)
    }

    override suspend fun search(query: String, page: Int): UserResult<SearchPage> {
        searchCalls++
        searchQueries += query
        return searchHandler(query, page)
    }

    override suspend fun addContact(user: UserSummary): UserResult<Unit> {
        val result = addHandler(user)
        if (result is UserResult.Success) {
            stored.value = stored.value + Contact(user, addedAtMillis = 0L)
        }
        return result
    }

    override suspend fun removeContact(userId: String): UserResult<Unit> {
        val result = removeHandler(userId)
        if (result is UserResult.Success) {
            stored.value = stored.value.filterNot { it.user.id == userId }
        }
        return result
    }

    fun setContacts(contacts: List<Contact>) {
        stored.value = contacts
    }
}

class FakeMessageRepository : MessageRepository {
    var openHandler: suspend (String) -> OpenDialogResult = { peerId ->
        OpenDialogResult.Opened("dialog-for-$peerId")
    }
    val openedPeers = mutableListOf<String>()
    val readDialogs = mutableListOf<String>()

    override suspend fun openDirectDialog(peer: UserSummary): OpenDialogResult {
        openedPeers += peer.id
        return openHandler(peer.id)
    }

    override fun observeDialogs(): Flow<List<Dialog>> = flowOf(emptyList())
    override fun observeDialogSummaries(selfId: String?): Flow<List<DialogSummary>> = flowOf(emptyList())
    override fun observeDialog(dialogId: String): Flow<Dialog?> = flowOf(null)
    override fun observeMessages(dialogId: String, limit: Long): Flow<List<Message>> = flowOf(emptyList())
    override fun observeSyncState(dialogId: String): Flow<DialogSyncState?> = flowOf(null)
    override suspend fun storedMessageCount(dialogId: String): Long = 0
    override suspend fun send(dialogId: String, text: String): Boolean = false
    override suspend fun markRead(dialogId: String) {
        readDialogs += dialogId
    }

    override suspend fun retry(localId: Long) = Unit
    override suspend fun loadOlder(dialogId: String) = Unit
}

fun userSummary(
    id: String,
    firstName: String = "First$id",
    lastName: String = "Last$id",
    email: String = "$id@relay.dev"
): UserSummary = UserSummary(id = id, email = email, firstName = firstName, lastName = lastName, avatarUrl = null)

fun searchResult(id: String, isContact: Boolean = false): UserSearchResult =
    UserSearchResult(user = userSummary(id), isContact = isContact)

fun contact(id: String): Contact = Contact(user = userSummary(id), addedAtMillis = 0L)
