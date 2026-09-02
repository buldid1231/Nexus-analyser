package com.meister.nexusradar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ModEntity::class, DependencyEntity::class, TagEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modDao(): ModDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mods ADD COLUMN fileSizeBytes INTEGER")
                database.execSQL("ALTER TABLE mods ADD COLUMN mainFilesCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE mods ADD COLUMN endorsements INTEGER")
                database.execSQL("ALTER TABLE mods ADD COLUMN uniqueDownloads INTEGER")
                database.execSQL("ALTER TABLE mods ADD COLUMN totalDownloads INTEGER")
                database.execSQL("ALTER TABLE mods ADD COLUMN requirementsCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE mods ADD COLUMN requiredByCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nexus_skyrim_radar.db")
                .addMigrations(MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
