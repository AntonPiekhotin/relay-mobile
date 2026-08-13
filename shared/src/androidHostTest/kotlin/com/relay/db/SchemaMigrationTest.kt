package com.relay.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

private val VERSION_1_SCHEMA = listOf(
    """
    CREATE TABLE dialog (
        id               TEXT    NOT NULL PRIMARY KEY,
        type             TEXT    NOT NULL,
        title            TEXT,
        last_message_at  INTEGER,
        unread_count     INTEGER NOT NULL DEFAULT 0
    )
    """,
    """
    CREATE TABLE message (
        local_id       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
        server_id      TEXT    UNIQUE,
        client_msg_id  TEXT    UNIQUE,
        dialog_id      TEXT    NOT NULL,
        sender_id      TEXT    NOT NULL,
        text           TEXT    NOT NULL,
        reply_to       TEXT,
        created_at     INTEGER NOT NULL,
        state          TEXT    NOT NULL,
        fail_reason    TEXT,
        attempt_count  INTEGER NOT NULL DEFAULT 0,
        next_retry_at  INTEGER,
        first_attempt_at INTEGER
    )
    """,
    """
    CREATE TABLE sync_state (
        dialog_id            TEXT NOT NULL PRIMARY KEY,
        newest_synced_id     TEXT,
        oldest_loaded_id     TEXT,
        has_more_history     INTEGER NOT NULL DEFAULT 1
    )
    """,
    "CREATE INDEX idx_message_dialog ON message(dialog_id, created_at DESC, local_id DESC)",
    "CREATE INDEX idx_message_outbox ON message(state, next_retry_at) WHERE state = 'PENDING'"
)

class SchemaMigrationTest {

    @Test
    fun schemaVersionMatchesTheNumberOfShippedMigrations() {
        assertEquals(4L, RelayDb.Schema.version)
    }

    @Test
    fun upgradingAPhaseThreeDatabaseAddsThePeerColumn() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VERSION_1_SCHEMA.forEach { driver.execute(null, it.trimIndent(), 0) }
        driver.execute(
            null,
            "INSERT INTO dialog(id, type, title, last_message_at) VALUES ('d1', 'direct', NULL, 1)",
            0
        )

        RelayDb.Schema.migrate(driver, oldVersion = 1, newVersion = RelayDb.Schema.version)

        val store = MessageStore(RelayDb(driver), Dispatchers.Unconfined)
        store.upsertDialog("d1", "direct", "Ada Lovelace", 1, peerId = "peer-1")
        val stored = store.observeDialogs().first().single()
        assertEquals("Ada Lovelace", stored.title)
        assertEquals("peer-1", stored.peerId)
    }

    @Test
    fun upgradingAPhaseTwoDatabaseAddsTheContactTable() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VERSION_1_SCHEMA.forEach { driver.execute(null, it.trimIndent(), 0) }

        RelayDb.Schema.migrate(driver, oldVersion = 1, newVersion = RelayDb.Schema.version)

        val contacts = ContactStore(RelayDb(driver), Dispatchers.Unconfined)
        contacts.clearAll()
        assertEquals(emptyList(), contacts.observeContacts().first())
    }

    @Test
    fun upgradingAPhaseThreeDatabaseAddsTheReadCursors() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VERSION_1_SCHEMA.forEach { driver.execute(null, it.trimIndent(), 0) }
        driver.execute(
            null,
            "INSERT INTO dialog(id, type, title, last_message_at) VALUES ('d1', 'direct', NULL, 1)",
            0
        )

        RelayDb.Schema.migrate(driver, oldVersion = 1, newVersion = RelayDb.Schema.version)

        val store = MessageStore(RelayDb(driver), Dispatchers.Unconfined)
        store.applyRemoteMessage("srv-1", null, "d1", "peer", "hello", 100, selfId = "me")
        assertEquals(1L, store.observeDialogSummaries("me").first().single().unreadCount)

        store.markSelfRead("d1", "srv-1", 100)
        assertEquals(listOf("srv-1"), store.unsentReads().map { it.upToMessageId })
        assertEquals(0L, store.observeDialogSummaries("me").first().single().unreadCount)

        store.applyReadReceipt("d1", "peer", "srv-1", readAt = 100, selfId = "me")
        assertEquals(100L, store.observeDialogs().first().single().peerReadAt)
    }

    @Test
    fun anUpgradedDatabaseKeepsItsExistingMessages() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        VERSION_1_SCHEMA.forEach { driver.execute(null, it.trimIndent(), 0) }
        driver.execute(
            null,
            """
            INSERT INTO message(server_id, client_msg_id, dialog_id, sender_id, text, created_at, state)
            VALUES ('srv-1', NULL, 'd1', 'peer', 'survives the upgrade', 1, 'SENT')
            """.trimIndent(),
            0
        )

        RelayDb.Schema.migrate(driver, oldVersion = 1, newVersion = RelayDb.Schema.version)

        val store = MessageStore(RelayDb(driver), Dispatchers.Unconfined)
        assertEquals(1L, store.countAllMessages())
    }
}
