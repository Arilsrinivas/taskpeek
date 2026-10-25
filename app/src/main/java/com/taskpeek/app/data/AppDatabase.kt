package com.taskpeek.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Task::class, Category::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN time TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN category TEXT NOT NULL DEFAULT 'Others'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatSchedule TEXT NOT NULL DEFAULT 'Does not repeat'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN reminder TEXT NOT NULL DEFAULT '10 minutes before'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL UNIQUE
                    )
                """)
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Study')")
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Work')")
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Personal')")
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Health')")
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Others')")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "taskpeek_database"
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Study')")
                        db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Work')")
                        db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Personal')")
                        db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Health')")
                        db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES ('Others')")
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
