package com.meister.nexusradar.domain

object ModFilter {
    private val blockedCategoryTerms = listOf(
        "translation", "translations", "localisation", "localization", "language",
        "images", "image", "screenshots", "videos", "video", "save games", "savegame"
    )
    private val translationTokens = listOf(
        "translation", "traduction", "traducción", "traduzione", "übersetzung", "tłumaczenie",
        "перевод", "翻译", "翻譯", "번역", "日本語化", "deutsch", "german", "french",
        "français", "spanish", "español", "italian", "polish", "russian", "chinese", "korean"
    )

    fun isActualMod(record: NexusModRecord): Boolean {
        if (!record.content_type.equals("MOD", ignoreCase = true)) return false
        val category = record.category.orEmpty().lowercase()
        if (blockedCategoryTerms.any { it in category }) return false

        val name = record.name.lowercase()
        val tags = record.tags.joinToString(" ").lowercase()
        val combined = "$name $tags"
        val looksLikeTranslation = translationTokens.any { token ->
            Regex("(^|[\\s\\-–—_()\\[\\]])${Regex.escape(token)}([\\s\\-–—_()\\[\\]]|$)", RegexOption.IGNORE_CASE).containsMatchIn(combined)
        }
        return !looksLikeTranslation
    }
}
