package com.relay

import android.app.Application
import com.relay.di.initKoin
import org.koin.android.ext.koin.androidContext

class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@RelayApplication)
        }
    }
}
