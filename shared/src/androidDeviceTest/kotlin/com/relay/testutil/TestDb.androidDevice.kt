package com.relay.testutil

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.relay.db.RelayDb

actual fun createTestSqlDriver(): SqlDriver =
    AndroidSqliteDriver(
        schema = RelayDb.Schema,
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        name = null
    )
