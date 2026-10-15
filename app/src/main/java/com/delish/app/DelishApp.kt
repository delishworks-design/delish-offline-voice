package com.delish.app

import android.app.Application

class DelishApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: DelishApp
            private set
    }
}