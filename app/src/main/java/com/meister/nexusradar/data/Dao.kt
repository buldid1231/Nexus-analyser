package com.meister.nexusradar.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ModDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMods(items: List<ModEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDependencies(items: List<DependencyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTags(items: List<TagEntity>)

    @Query("DELETE FROM tags WHERE modId IN (:ids)")
    suspend fun deleteTagsFor(ids: List<Long>)

    @Query("DELETE FROM dependencies WHERE ownerModId IN (:ids)")
    suspend fun deleteDependenciesFor(ids: List<Long>)

    @Query("DELETE FROM dependencies WHERE ownerModId = :id AND relationType = :relationType")
    suspend fun deleteDependenciesFor(id: Long, relationType: String)

    @Query("SELECT * FROM mods ORDER BY COALESCE(updatedAt, publishedAt, lastSeenAt) DESC")
    fun observeAll(): Flow<List<ModEntity>>

    @Query("SELECT * FROM mods WHERE modId = :id LIMIT 1")
    suspend fun byId(id: Long): ModEntity?

    @Query("SELECT * FROM mods WHERE modId IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ModEntity>

    @Query("SELECT COUNT(*) FROM mods")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM mods WHERE inSelectedRange = 1")
    suspend fun countInRange(): Int

    @Query("SELECT COUNT(*) FROM mods WHERE inSelectedRange = 1 AND collectionState IN ('NEW','UPDATED')")
    suspend fun countChangedInRange(): Int

    @Query("SELECT COUNT(*) FROM mods WHERE changedAt IS NOT NULL AND (lastExportedAt IS NULL OR changedAt > lastExportedAt)")
    suspend fun countPendingExport(): Int

    @Query("SELECT * FROM mods ORDER BY modId LIMIT :limit OFFSET :offset")
    suspend fun chunk(limit: Int, offset: Int): List<ModEntity>

    @Query("SELECT * FROM mods WHERE inSelectedRange = 1 ORDER BY COALESCE(updatedAt, publishedAt, lastSeenAt) DESC LIMIT :limit OFFSET :offset")
    suspend fun rangeChunk(limit: Int, offset: Int): List<ModEntity>

    @Query("SELECT * FROM mods WHERE inSelectedRange = 1 AND collectionState IN ('NEW','UPDATED') ORDER BY COALESCE(updatedAt, publishedAt, lastSeenAt) DESC LIMIT :limit OFFSET :offset")
    suspend fun changedRangeChunk(limit: Int, offset: Int): List<ModEntity>

    @Query("SELECT * FROM mods WHERE changedAt IS NOT NULL AND (lastExportedAt IS NULL OR changedAt > lastExportedAt) ORDER BY changedAt DESC, modId LIMIT :limit OFFSET :offset")
    suspend fun pendingExportChunk(limit: Int, offset: Int): List<ModEntity>

    @Query("UPDATE mods SET lastExportedAt = :exportedAt WHERE modId IN (:ids)")
    suspend fun markExported(ids: List<Long>, exportedAt: String)

    @Query("SELECT * FROM dependencies WHERE ownerModId IN (:ids)")
    suspend fun dependenciesFor(ids: List<Long>): List<DependencyEntity>

    @Query("SELECT * FROM tags WHERE modId IN (:ids)")
    suspend fun tagsFor(ids: List<Long>): List<TagEntity>

    @Query("SELECT * FROM mods ORDER BY modId")
    suspend fun allMods(): List<ModEntity>

    @Query("SELECT * FROM dependencies ORDER BY ownerModId, relationType, relatedModId")
    suspend fun allDependencies(): List<DependencyEntity>

    @Query("SELECT * FROM tags ORDER BY modId, tag")
    suspend fun allTags(): List<TagEntity>

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM dependencies")
    suspend fun deleteAllDependencies()

    @Query("DELETE FROM mods")
    suspend fun deleteAllMods()

    @Transaction
    suspend fun replaceCatalog(snapshot: CatalogSnapshot) {
        deleteAllTags()
        deleteAllDependencies()
        deleteAllMods()
        if (snapshot.mods.isNotEmpty()) upsertMods(snapshot.mods)
        if (snapshot.dependencies.isNotEmpty()) upsertDependencies(snapshot.dependencies)
        if (snapshot.tags.isNotEmpty()) upsertTags(snapshot.tags)
    }
}
