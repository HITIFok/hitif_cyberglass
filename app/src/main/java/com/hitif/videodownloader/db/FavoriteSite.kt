package com.hitif.videodownloader.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorite_sites",
    indices = [Index(value = ["url"], unique = true)]
)
data class FavoriteSite(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String = "",
    val addedAt: Long = System.currentTimeMillis()
)
