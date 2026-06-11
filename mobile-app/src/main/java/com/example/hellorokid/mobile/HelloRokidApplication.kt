package com.example.hellorokid.mobile

import android.app.Application
import com.example.hellorokid.mobile.ble.PhoneBleClient
import com.example.hellorokid.mobile.data.AppDatabase
import com.example.hellorokid.mobile.data.CardRepository

class HelloRokidApplication : Application() {

    val cardRepository: CardRepository by lazy {
        CardRepository(AppDatabase.getInstance(this).businessCardDao())
    }

    val bleClient: PhoneBleClient by lazy {
        PhoneBleClient(this)
    }
}
