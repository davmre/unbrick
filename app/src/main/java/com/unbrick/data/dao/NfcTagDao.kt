package com.unbrick.data.dao

import androidx.room.*
import com.unbrick.data.model.NfcTagInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface NfcTagDao {
    @Query("SELECT * FROM nfc_tags")
    fun getAllTags(): Flow<List<NfcTagInfo>>

    @Query("SELECT * FROM nfc_tags LIMIT 1")
    suspend fun getPrimaryTag(): NfcTagInfo?

    @Query("SELECT EXISTS(SELECT 1 FROM nfc_tags WHERE tagId = :tagId)")
    suspend fun isTagRegistered(tagId: String): Boolean

    @Query("SELECT COUNT(*) FROM nfc_tags")
    suspend fun getTagCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: NfcTagInfo)

    @Delete
    suspend fun delete(tag: NfcTagInfo)

    @Query("DELETE FROM nfc_tags")
    suspend fun deleteAll()
}
