package com.usbdiskmanager.di

import com.usbdiskmanager.core.util.PrivilegedCommandRunner
import com.usbdiskmanager.shizuku.ShizukuCommandRunner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that binds ShizukuCommandRunner as the PrivilegedCommandRunner.
 * Injected into UsbDeviceRepositoryImpl (in :usb module).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShizukuModule {

    @Binds
    @Singleton
    abstract fun bindPrivilegedRunner(
        impl: ShizukuCommandRunner
    ): PrivilegedCommandRunner
}
