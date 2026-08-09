package com.relay.testutil

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import com.relay.db.RelayDb

actual fun createTestSqlDriver(): SqlDriver = inMemoryDriver(RelayDb.Schema)
