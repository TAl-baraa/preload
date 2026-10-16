package com.preload.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class DownloadJobConverters {
    @TypeConverter
    fun fromMediaKind(value: DownloadMediaKind): String = value.name

    @TypeConverter
    fun toMediaKind(value: String): DownloadMediaKind = DownloadMediaKind.valueOf(value)

    @TypeConverter
    fun fromStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}

@Database(entities = [DownloadJob::class], version = 1, exportSchema = true)
@TypeConverters(DownloadJobConverters::class)
abstract class PreloadDatabase : RoomDatabase() {
    abstract fun downloadJobDao(): DownloadJobDao

    companion object {
        @Volatile
        private var instance: PreloadDatabase? = null

        fun getInstance(context: Context): PreloadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PreloadDatabase::class.java,
                "preload.db",
            ).build().also { instance = it }
        }
    }
}
