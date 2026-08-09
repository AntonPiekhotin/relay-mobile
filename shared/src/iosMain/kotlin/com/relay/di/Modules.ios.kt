package com.relay.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.relay.auth.IosTokenStore
import com.relay.auth.TokenStore
import com.relay.config.AppConfig
import com.relay.db.RelayDb
import org.koin.core.module.Module
import org.koin.dsl.module

private data class IosDevConfig(
    override val apiBaseUrl: String = "http://localhost:8080",
    override val wsUrl: String = "ws://localhost:8083/ws"
) : AppConfig

actual val platformModule: Module = module {
    single<AppConfig> { IosDevConfig() }
    single<TokenStore> { IosTokenStore() }
    single<SqlDriver> { NativeSqliteDriver(RelayDb.Schema, "relay.db") }
}
