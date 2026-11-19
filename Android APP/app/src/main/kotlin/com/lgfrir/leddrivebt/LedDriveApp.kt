package com.lgfrir.leddrivebt

import android.app.Application
import com.lgfrir.leddrivebt.ble.LightBleClient
import com.lgfrir.leddrivebt.ui.ClockLibrary

class LightSession(app: Application) {
    val ble = LightBleClient(app)
    val library = ClockLibrary(ble)
}

class LedDriveApp : Application() {
    lateinit var session: LightSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = LightSession(this)
    }

    companion object {
        fun session(app: Application): LightSession = (app as LedDriveApp).session
    }
}
