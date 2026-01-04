package com.unbrick.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocking_profiles",
    indices = [Index("isActive")]
)
data class BlockingProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val blockingMode: String = BlockingMode.BLOCKLIST.name,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
