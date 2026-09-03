package com.meister.nexusradar.domain

import kotlinx.serialization.Serializable

@Serializable data class NexusRelation(val mod_id: Long, val name: String, val url: String? = null)

@Serializable
data class NexusModRecord(
    val mod_id: Long,
    val name: String,
    val author: String? = null,
    val version: String? = null,
    val category: String? = null,
    val summary: String? = null,
    val published_at: String? = null,
    val updated_at: String? = null,
    val adult: Boolean = false,
    val url: String? = null,
    val file_size_bytes: Long? = null,
    val main_files_count: Int = 0,
    val endorsements: Long? = null,
    val unique_downloads: Long? = null,
    val total_downloads: Long? = null,
    val tags: List<String> = emptyList(),
    val requirements: List<NexusRelation> = emptyList(),
    val required_by: List<NexusRelation> = emptyList(),
    val requirements_count: Int = 0,
    val required_by_count: Int = 0,
    val content_type: String = "MOD",
    val collection_state: String? = null,
    val in_selected_range: Boolean? = null,
    val diagnostics: List<String> = emptyList()
)

@Serializable
data class ImportChunk(
    val schema_version: Int = 8,
    val game: String = "skyrimspecialedition",
    val generated_at: String,
    val scan_started_at: String? = null,
    val range_start: String? = null,
    val range_end: String? = null,
    val chunk: Int = 1,
    val chunk_size: Int = 100,
    val mods: List<NexusModRecord>
)

@Serializable
data class NexusFileMetrics(
    val file_size_bytes: Long? = null,
    val main_files_count: Int = 0
)

@Serializable
data class VisibleLink(
    val mod_id: Long,
    val url: String,
    val name: String = "",
    val updated_at: String? = null,
    val version: String? = null
)

@Serializable data class VisibleLinksResult(
    val kind: String = "links",
    val url: String? = null,
    val next_url: String? = null,
    val links: List<VisibleLink> = emptyList()
)

@Serializable
data class ListingAdvanceResult(
    val action: String = "none",
    val next_url: String? = null
)
