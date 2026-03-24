package com.usbdiskmanager.usb.di

import com.usbdiskmanager.usb.api.UsbDeviceRepository
import com.usbdiskmanager.usb.impl.UsbDeviceRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UsbModule {

    @Binds
    @Singleton
    abstract fun bindUsbDeviceRepository(
        impl: UsbDeviceRepositoryImpl
    ): UsbDeviceRepository
}
