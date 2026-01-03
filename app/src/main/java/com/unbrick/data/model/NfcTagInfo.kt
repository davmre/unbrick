package com.unbrick.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nfc_tags")
data class NfcTagInfo(
    @PrimaryKey
    val tagId: String, // Hex representation of the tag's unique ID
    val registeredAt: Long = System.currentTimeMillis(),
    val name: String = "Primary Tag"
)
