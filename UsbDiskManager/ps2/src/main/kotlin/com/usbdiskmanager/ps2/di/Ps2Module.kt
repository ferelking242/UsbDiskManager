package com.usbdiskmanager.ps2.di

import android.content.Context
import com.usbdiskmanager.ps2.data.Ps2RepositoryImpl
import com.usbdiskmanager.ps2.data.converter.UlCfgManager
import com.usbdiskmanager.ps2.data.cover.CoverArtFetcher
import com.usbdiskmanager.ps2.data.db.ConversionJobDao
import com.usbdiskmanager.ps2.data.db.Ps2Database
import com.usbdiskmanager.ps2.data.download.TelegramDownloadDao
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.domain.repository.Ps2Repository
import com.usbdiskmanager.ps2.engine.IsoEngine
import com.usbdiskmanager.ps2.engine.KotlinIsoEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class Ps2Module {

    /**
     * Binds [KotlinIsoEngine] as the active [IsoEngine].
     *
     * To switch to [NativeIsoEngine] in the future, change this single line.
     * Zero changes required in the repository, scanner, or UI.
     */
    @Binds
    @Singleton
    abstract fun bindIsoEngine(impl: KotlinIsoEngine): IsoEngine

    @Binds
    @Singleton
    abstract fun bindPs2Repository(impl: Ps2RepositoryImpl): Ps2Repository

    companion object {
        @Provides
        @Singleton
        fun providePs2Database(@ApplicationContext context: Context): Ps2Database =
            Ps2Database.getInstance(context)

        @Provides
        @Singleton
        fun provideConversionJobDao(db: Ps2Database): ConversionJobDao =
            db.conversionJobDao()

        @Provides
        @Singleton
        fun provideTelegramDownloadDao(db: Ps2Database): TelegramDownloadDao =
            db.telegramDownloadDao()
    }
}
