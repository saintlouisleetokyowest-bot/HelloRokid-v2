package com.example.hellorokid.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BusinessCardEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun businessCardDao(): BusinessCardDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE business_cards ADD COLUMN department TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE business_cards ADD COLUMN mobile TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE business_cards ADD COLUMN fax TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rokid_cards.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
        }
    }
}
