package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.Calendar
import java.util.Locale

@CloudstreamPlugin
class AnimeUnityPlugin : Plugin() {
    companion object {
        const val PREFS_NAME = "AnimeUnity"
        const val PREF_SITE_URL = "siteUrl"

        // Sezioni abilitate
        const val PREF_SHOW_LATEST_EPISODES = "showLatestEpisodes"
        const val PREF_SHOW_CALENDAR = "showCalendar"
        const val PREF_SHOW_RANDOM = "showRandom"
        const val PREF_SHOW_ONGOING = "showOngoing"
        const val PREF_SHOW_POPULAR = "showPopular"
        const val PREF_SHOW_BEST = "showBest"
        const val PREF_SHOW_UPCOMING = "showUpcoming"

        // Count per sezione
        const val PREF_LATEST_COUNT = "latestCount"
        const val PREF_CALENDAR_COUNT = "calendarCount"
        const val PREF_ONGOING_COUNT = "ongoingCount"
        const val PREF_POPULAR_COUNT = "popularCount"
        const val PREF_BEST_COUNT = "bestCount"
        const val PREF_UPCOMING_COUNT = "upcomingCount"
        const val PREF_RANDOM_COUNT = "randomCount"

        // Nomi personalizzati per sezione
        const val PREF_LATEST_TITLE = "latestTitle"
        const val PREF_CALENDAR_TITLE = "calendarTitle"
        const val PREF_ONGOING_TITLE = "ongoingTitle"
        const val PREF_POPULAR_TITLE = "popularTitle"
        const val PREF_BEST_TITLE = "bestTitle"
        const val PREF_UPCOMING_TITLE = "upcomingTitle"
        const val PREF_RANDOM_TITLE = "randomTitle"

        // Visualizzazione
        const val PREF_UNIFY_DUB_SUB_CARDS = "unifyDubSubCards"
        const val PREF_SHOW_DUB_SUB = "showDubSub"
        const val PREF_SHOW_EPISODE_NUMBER = "showEpisodeNumber"
        const val PREF_SECTION_ORDER = "sectionOrder"
        const val PREF_ENABLE_ADVANCED_SEARCH = "enableAdvancedSearch"
        const val PREF_ADVANCED_SEARCH_TITLE = "advancedSearchTitle"
        const val PREF_ADVANCED_SEARCH_GENRE_ID = "advancedSearchGenreId"
        const val PREF_ADVANCED_SEARCH_YEAR = "advancedSearchYear"
        const val PREF_ADVANCED_SEARCH_ORDER = "advancedSearchOrder"
        const val PREF_ADVANCED_SEARCH_STATUS = "advancedSearchStatus"
        const val PREF_ADVANCED_SEARCH_TYPE = "advancedSearchType"
        const val PREF_ADVANCED_SEARCH_SEASON = "advancedSearchSeason"
        const val PREF_ADVANCED_SEARCH_COUNT = "advancedSearchCount"
        const val PREF_CACHE_MAX_ENTRIES = "cacheMaxEntries"
        const val PREF_CACHE_MAX_SIZE_MB = "cacheMaxSizeMb"

        const val DEFAULT_SITE_URL = "https://animeunity.cx/"
        const val DEFAULT_SECTION_COUNT = 30
        const val MAX_SECTION_COUNT = 100
        const val DEFAULT_ADVANCED_SEARCH_COUNT = MAX_SECTION_COUNT
        const val DEFAULT_SECTION_ORDER = "latest,calendar,random,ongoing,popular,best,upcoming"
        const val DEFAULT_UNIFY_DUB_SUB_CARDS = true
        const val DEFAULT_CACHE_MAX_ENTRIES = 1000
        const val MIN_CACHE_MAX_ENTRIES = 50
        const val MAX_CACHE_MAX_ENTRIES = 10000
        const val DEFAULT_CACHE_MAX_SIZE_MB = 250
        const val MIN_CACHE_MAX_SIZE_MB = 16
        const val MAX_CACHE_MAX_SIZE_MB = 2048
        private const val ARCHIVE_OLDEST_YEAR = 1966
        private val defaultSectionKeys = DEFAULT_SECTION_ORDER.split(",")
        private val validSectionKeys = listOf(
            "latest",
            "calendar",
            "ongoing",
            "popular",
            "best",
            "upcoming",
            "random"
        )

        private val siteSchemeRegex = Regex("""(?i)^https?://""")
        private val validSiteHostRegex = Regex(
            pattern = """^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""",
            option = RegexOption.IGNORE_CASE
        )
        private val legacyHosts = setOf(
            "animeunity.co",
            "www.animeunity.co",
            "animeunity.so",
            "www.animeunity.so",
            "animeunity.to",
            "www.animeunity.to",
            "animeunity.tv",
            "www.animeunity.tv",
            "animeunity.it",
            "www.animeunity.it"
        )

        private val advancedSearchGenreOptions = listOf(
            ArchiveGenreOption(51, "Action"),
            ArchiveGenreOption(21, "Adventure"),
            ArchiveGenreOption(43, "Avant Garde"),
            ArchiveGenreOption(59, "Boys Love"),
            ArchiveGenreOption(37, "Comedy"),
            ArchiveGenreOption(13, "Demons"),
            ArchiveGenreOption(22, "Drama"),
            ArchiveGenreOption(5, "Ecchi"),
            ArchiveGenreOption(9, "Fantasy"),
            ArchiveGenreOption(44, "Game"),
            ArchiveGenreOption(58, "Girls Love"),
            ArchiveGenreOption(52, "Gore"),
            ArchiveGenreOption(56, "Gourmet"),
            ArchiveGenreOption(15, "Harem"),
            ArchiveGenreOption(4
