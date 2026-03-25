package com.usbdiskmanager.ps2.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.usbdiskmanager.ps2.domain.model.ConversionJob

@Database(entities = [ConversionJob::class], version = 1, exportSchema = false)
abstract class Ps2Database : RoomDatabase() {
    abstract fun conversionJobDao(): ConversionJobDao

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
