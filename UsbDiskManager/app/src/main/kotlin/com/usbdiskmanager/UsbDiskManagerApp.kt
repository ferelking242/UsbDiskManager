package com.usbdiskmanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class UsbDiskManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("UsbDiskManager started")
    }
}
