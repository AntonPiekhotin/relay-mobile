package com.relay.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.relay.model.UserSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.relay.model.Contact as DomainContact

class ContactStore(
    private val db: RelayDb,
    private val dispatcher: CoroutineDispatcher
) {
    fun observeContacts(): Flow<List<DomainContact>> =
        db.contactQueries.selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun replaceAll(contacts: List<DomainContact>): Unit = withContext(dispatcher) {
        db.transaction {
            db.contactQueries.deleteAll()
            contacts.forEach { db.contactQueries.upsertOf(it) }
        }
    }

    suspend fun upsert(contact: DomainContact): Unit = withContext(dispatcher) {
        db.contactQueries.upsertOf(contact)
    }

    suspend fun remove(userId: String): Unit = withContext(dispatcher) {
        db.contactQueries.deleteByUserId(userId)
    }

    suspend fun clearAll(): Unit = withContext(dispatcher) {
        db.contactQueries.deleteAll()
    }
}

private fun Contact.toDomain(): DomainContact =
    DomainContact(
        user = UserSummary(
            id = user_id,
            email = email,
            firstName = first_name,
            lastName = last_name,
            avatarUrl = avatar_url
        ),
        addedAtMillis = added_at
    )

private fun ContactQueries.upsertOf(contact: DomainContact) {
    upsert(
        userId = contact.user.id,
        email = contact.user.email,
        firstName = contact.user.firstName,
        lastName = contact.user.lastName,
        avatarUrl = contact.user.avatarUrl,
        addedAt = contact.addedAtMillis
    )
}
