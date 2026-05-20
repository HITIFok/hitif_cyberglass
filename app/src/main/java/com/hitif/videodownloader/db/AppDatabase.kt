package com.hitif.videodownloader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadRecord::class, FavoriteSite::class],
    version  = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN progressBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_history ADD COLUMN totalBytes INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE download_history ADD COLUMN speedBps INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_sites (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_favorite_sites_url ON favorite_sites (url)")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hitif_downloads.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
            }
    }
}
