package com.example.hellorokid.mobile.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BusinessCardDao {

    @Query("SELECT * FROM business_cards ORDER BY scannedAt DESC")
    fun observeAll(): Flow<List<BusinessCardEntity>>

    @Query("SELECT * FROM business_cards ORDER BY scannedAt DESC")
    suspend fun getAll(): List<BusinessCardEntity>

    @Query("SELECT * FROM business_cards WHERE id = :id")
    suspend fun getById(id: Long): BusinessCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: BusinessCardEntity): Long

    @Update
    suspend fun update(card: BusinessCardEntity)

    @Delete
    suspend fun delete(card: BusinessCardEntity)

    @Query("DELETE FROM business_cards WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM business_cards")
    fun observeCount(): Flow<Int>
}
