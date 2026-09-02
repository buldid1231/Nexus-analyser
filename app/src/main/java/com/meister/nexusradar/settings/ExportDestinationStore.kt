package com.meister.nexusradar.settings

import android.content.Context
import android.net.Uri

class ExportDestinationStore(context: Context) {
    private val prefs = context.getSharedPreferences("export_destination", Context.MODE_PRIVATE)

    fun load(): Uri? = prefs.getString("tree_uri", null)?.let(Uri::parse)

    fun save(uri: Uri) {
        prefs.edit().putString("tree_uri", uri.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("tree_uri").apply()
    }
}
