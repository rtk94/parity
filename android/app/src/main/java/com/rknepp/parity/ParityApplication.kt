package com.rknepp.parity

import android.app.Application
import com.google.crypto.tink.aead.AeadConfig

class ParityApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        AeadConfig.register()
        serviceLocator = ServiceLocator(this)
    }
}
