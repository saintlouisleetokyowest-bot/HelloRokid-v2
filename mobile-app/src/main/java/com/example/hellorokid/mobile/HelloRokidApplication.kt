package com.example.hellorokid.mobile

import android.app.Application
import com.example.hellorokid.mobile.ble.PhoneBleClient
import com.example.hellorokid.mobile.data.AppDatabase
import com.example.hellorokid.mobile.data.CardRepository
import com.example.hellorokid.mobile.locale.AppLocaleManager

class HelloRokidApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.applySavedLocale(this)
    }

    val cardRepository: CardRepository by lazy {
        CardRepository(AppDatabase.getInstance(this).businessCardDao())
    }

    val bleClient: PhoneBleClient by lazy {
        PhoneBleClient(this)
    }
}
