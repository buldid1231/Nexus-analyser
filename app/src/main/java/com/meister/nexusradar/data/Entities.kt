package com.meister.nexusradar.data

import androidx.room.Entity

@Entity(tableName = "mods")
data class ModEntity(
    @androidx.room.PrimaryKey val modId: Long,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val category: String? = null,
    val summary: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    val adult: Boolean = false,
    val nexusUrl: String? = null,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val hasSkseHint: Boolean = false,
    val hasDllHint: Boolean = false,
    val collectionState: String = "DISCOVERED",
    val inSelectedRange: Boolean = true,
    val diagnostics: String = ""
)

@Entity(tableName = "dependencies", primaryKeys = ["ownerModId", "relatedModId", "relationType"])
data class DependencyEntity(
    val ownerModId: Long,
    val relatedModId: Long,
    val relatedName: String,
    val relatedUrl: String? = null,
    val relationType: String
)

@Entity(tableName = "tags", primaryKeys = ["modId", "tag"])
data class TagEntity(val modId: Long, val tag: String)
