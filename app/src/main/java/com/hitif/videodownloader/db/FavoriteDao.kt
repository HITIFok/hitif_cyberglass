package com.hitif.videodownloader.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteSite): Long

    @Query("SELECT * FROM favorite_sites ORDER BY addedAt DESC")
    fun observeAll(): LiveData<List<FavoriteSite>>

    @Query("SELECT * FROM favorite_sites ORDER BY addedAt DESC")
    suspend fun getAll(): List<FavoriteSite>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_sites WHERE url = :url LIMIT 1)")
    suspend fun exists(url: String): Boolean

    @Query("DELETE FROM favorite_sites WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Delete
    suspend fun delete(favorite: FavoriteSite)

    @Query("DELETE FROM favorite_sites")
    suspend fun deleteAll()
}
