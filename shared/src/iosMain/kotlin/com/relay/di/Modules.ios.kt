package com.relay.di

import com.relay.auth.IosTokenStore
import com.relay.auth.TokenStore
import com.relay.config.AppConfig
import org.koin.core.module.Module
import org.koin.dsl.module

private data class IosDevConfig(
    override val apiBaseUrl: String = "http://localhost:8080",
    override val wsUrl: String = "ws://localhost:8083/ws"
) : AppConfig

actual val platformModule: Module = module {
    single<AppConfig> { IosDevConfig() }
    single<TokenStore> { IosTokenStore() }
}
