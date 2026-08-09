package com.relay.testutil

import app.cash.sqldelight.db.SqlDriver
import com.relay.db.RelayDb

expect fun createTestSqlDriver(): SqlDriver

fun createTestDb(): RelayDb = RelayDb(createTestSqlDriver())
