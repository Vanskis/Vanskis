package com.vanskis.liquidpocket.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Chats and remembered facts. Bump [version] with a migration when an entity changes. */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class, FactEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "liquid-pocket.db").build()
    }
}
