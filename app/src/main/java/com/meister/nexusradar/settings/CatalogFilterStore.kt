package com.meister.nexusradar.settings

import android.content.Context
import com.meister.nexusradar.domain.CatalogFilterState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CatalogFilterStore(context: Context) {
    private val prefs = context.getSharedPreferences("catalog_filters", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): CatalogFilterState = runCatching {
        json.decodeFromString<CatalogFilterState>(prefs.getString(KEY_FILTERS, "") ?: "")
    }.getOrDefault(CatalogFilterState())

    fun save(filters: CatalogFilterState) {
        prefs.edit().putString(KEY_FILTERS, json.encodeToString(filters)).apply()
    }

    companion object {
        private const val KEY_FILTERS = "filters"
    }
}
