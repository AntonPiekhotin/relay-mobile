package com.relay.di

import com.relay.auth.AndroidTokenStore
import com.relay.auth.TokenStore
import com.relay.config.AppConfig
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private data class AndroidDevConfig(
    override val apiBaseUrl: String = "http://10.0.2.2:8080",
    override val wsUrl: String = "ws://10.0.2.2:8083/ws"
) : AppConfig

actual val platformModule: Module = module {
    single<AppConfig> { AndroidDevConfig() }
    single<TokenStore> { AndroidTokenStore(androidContext()) }
}
