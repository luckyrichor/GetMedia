package com.getmedia.app

import android.app.Application

class GetMediaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.getmedia.app.data.repo.DiagnosticsStore.initialize(this)
        com.getmedia.app.account.AccountService.initialize(this)
    }
}
