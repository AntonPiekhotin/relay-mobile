package com.relay.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.relay.auth.AndroidTokenStore
import com.relay.auth.TokenStore
import com.relay.config.AppConfig
import com.relay.db.RelayDb
import com.relay.push.PushPlatform
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private data class AndroidDevConfig(
    override val apiBaseUrl: String = "http://10.0.2.2:8080",
    override val wsUrl: String = "ws://10.0.2.2:8083/ws"
) : AppConfig

actual val platformModule: Module = module {
    single<AppConfig> { AndroidDevConfig() }
    single { PushPlatform("android") }
    single<TokenStore> { AndroidTokenStore(androidContext()) }
    single<SqlDriver> { AndroidSqliteDriver(RelayDb.Schema, androidContext(), "relay.db") }
}
