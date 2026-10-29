package com.vanskis.liquidpocket.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Notes the assistant saves with the create_note tool and reads back with list_notes. */
@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    /** Newest first; [query] matches the title or the text, null matches all. */
    @Query(
        "SELECT * FROM notes WHERE :query IS NULL OR title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    suspend fun find(query: String?, limit: Int): List<NoteEntity>
}
