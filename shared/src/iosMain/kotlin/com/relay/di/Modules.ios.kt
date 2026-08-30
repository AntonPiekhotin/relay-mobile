package com.relay.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.relay.auth.IosTokenStore
import com.relay.auth.TokenStore
import com.relay.call.BridgedRtcClientFactory
import com.relay.call.BridgedSfuClientFactory
import com.relay.call.MicPermission
import com.relay.call.RtcClientFactory
import com.relay.call.SfuClientFactory
import com.relay.config.AppConfig
import com.relay.db.RelayDb
import com.relay.push.IosAppLifecycle
import com.relay.push.IosNotificationPresenter
import com.relay.push.PushPlatform
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle

private const val DEFAULT_DEV_HOST = "localhost"

private fun infoPlistString(key: String): String? =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)?.takeIf { it.isNotBlank() }

private fun devHost(): String = infoPlistString("RelayServerHost") ?: DEFAULT_DEV_HOST

private data class IosDevConfig(
    private val host: String = devHost(),
    override val apiBaseUrl: String = infoPlistString("RelayApiBaseUrl") ?: "http://$host:8080",
    override val wsUrl: String = infoPlistString("RelayWsUrl") ?: "ws://$host:8083/ws"
) : AppConfig

actual val platformModule: Module = module {
    single<AppConfig> { IosDevConfig() }
    single { PushPlatform("ios") }
    single<RtcClientFactory> { BridgedRtcClientFactory() }
    single<SfuClientFactory> { BridgedSfuClientFactory() }
    single { MicPermission(grantedByPlatform = true) }
    single { IosNotificationPresenter() }
    single { IosAppLifecycle(get(), get(), get(), get(), get(), get()) }
    single<TokenStore> { IosTokenStore() }
    single<SqlDriver> { NativeSqliteDriver(RelayDb.Schema, "relay.db") }
}
