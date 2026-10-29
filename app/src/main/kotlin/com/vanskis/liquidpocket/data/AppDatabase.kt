package com.vanskis.liquidpocket.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Chats, remembered facts and notes. Bump [version] with a migration when an entity changes. */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, FactEntity::class, NoteEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun noteDao(): NoteDao

    companion object {
        /** Version 2 adds the notes table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "liquid-pocket.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
