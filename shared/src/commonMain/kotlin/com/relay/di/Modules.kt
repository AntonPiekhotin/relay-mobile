package com.relay.di

import com.relay.auth.SessionManager
import com.relay.concurrency.ioDispatcher
import com.relay.db.MessageStore
import com.relay.db.RelayDb
import com.relay.network.AuthApi
import com.relay.network.ConnectionManager
import com.relay.network.HttpClientFactory
import com.relay.network.KtorAuthApi
import com.relay.network.KtorMessageApi
import com.relay.network.MessageApi
import com.relay.network.SocketClient
import com.relay.repository.MessageRepository
import com.relay.repository.MessageRepositoryImpl
import com.relay.sync.Outbox
import com.relay.sync.SyncEngine
import com.relay.ui.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val commonModule = module {
    single { HttpClientFactory.create() }
    single<AuthApi> { KtorAuthApi(get(), get()) }
    single { SessionManager(get(), get()) }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { ConnectionManager(get(), get(), get(), get()) }
    single<SocketClient> { get<ConnectionManager>() }
    single { RelayDb(get()) }
    single { MessageStore(get(), ioDispatcher()) }
    single<MessageApi> { KtorMessageApi(get(), get(), get()) }
    single { Outbox(get(), get(), get(), get()) }
    single { SyncEngine(get(), get(), get(), get(), get()) }
    single<MessageRepository> { MessageRepositoryImpl(get(), get(), get(), get()) }
    factory { SessionViewModel(get(), get(), get(), get(), get()) }
}

expect val platformModule: Module

fun initKoin(appDeclaration: KoinAppDeclaration? = null) {
    startKoin {
        appDeclaration?.invoke(this)
        modules(commonModule, platformModule)
    }
}
