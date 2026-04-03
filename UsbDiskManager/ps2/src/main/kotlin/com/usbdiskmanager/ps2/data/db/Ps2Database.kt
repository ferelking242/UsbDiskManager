package com.usbdiskmanager.ps2.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.usbdiskmanager.ps2.data.download.TelegramDownloadDao
import com.usbdiskmanager.ps2.data.download.TelegramDownloadEntity
import com.usbdiskmanager.ps2.domain.model.ConversionJob

@Database(
    entities = [ConversionJob::class, TelegramDownloadEntity::class],
    version = 3,
    exportSchema = false
)
abstract class Ps2Database : RoomDatabase() {
    abstract fun conversionJobDao(): ConversionJobDao
    abstract fun telegramDownloadDao(): TelegramDownloadDao

    companion object {
        @Volatile
        private var INSTANCE: Ps2Database? = null

        fun getInstance(context: Context): Ps2Database {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    Ps2Database::class.java,
                    "ps2_manager.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
