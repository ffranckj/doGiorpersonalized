package it.dogior.hadEnough

import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Str = BooleanOrString.AsString

@Suppress("unused")
class AnimeUnity(
    private val sharedPref: SharedPreferences? = null,
) : MainAPI() {
    @Volatile
    private var resolvedBaseUrl: String? = null

    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        get() = resolvedBaseUrl ?: AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        set(value) {
            field = value
        }
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        private const val LOG_TAG = "AU_DEBUG"

        @Suppress("ConstPropertyName")
        const val mainUrl = AnimeUnityPlugin.DEFAULT_SITE_URL
        const val ARCHIVE_BATCH_SIZE = 30
        const val advancedSearchSectionName = "Ricerca avanzata"
        const val latestEpisodesSectionName = "Ultimi Episodi"
        const val calendarSectionName = "Calendario"
        const val randomSectionName = "Random"
        const val ongoingSectionName = "In Corso"
        const val popularSectionName = "Popolari"
        const val bestSectionName = "I migliori"
        const val upcomingSectionName = "In Arrivo"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        var name = "AnimeUnity"

        @Volatile
        var headers = mutableMapOf(
            "User-Agent" to USER_AGENT
        )
    }

    private data class MainPageSectionData(
        val key: String,
        val baseUrl: String,
    )

    private data class GroupedAnimeCard(
        val anime: Anime,
        val hasDub: Boolean,
    )

    private data class GroupedLatestEpisodeCard(
        val anime: LatestEpisodeAnime,
        val hasDub: Boolean,
        val episodeNumber: String,
    )

    private data class AnimePageData(
        val anime: Anime,
        val relatedAnime: List<Anime>,
        val episodes: List<Episode>,
    )

    private data class EpisodeSource(
        val number: String,
        val url: String,
        val directEmbedUrl: String? = null,
    )

    data class EpisodePlaybackData(
        @JsonProperty("preferredUrl") val preferredUrl: String,
        @JsonProperty("subUrl") val subUrl: String? = null,
        @JsonProperty("dubUrl") val dubUrl: String? = null,
        @JsonProperty("embedUrl") val embedUrl: String? = null,
    )

    private data class PlayerSourceOption(
        val label: String,
        val url: String,
    )

    override val mainPage: List<MainPageData>
        get() = buildSectionNamesList()

    private fun encodeMainPageSectionData(sectionKey: String, baseUrl: String): String {
        return "$sectionKey|$baseUrl"
    }

    private fun decodeMainPageSectionData(data: String): MainPageSectionData {
        val separatorIndex = data.indexOf('|')
        return if (separatorIndex == -1) {
            MainPageSectionData(key = data, baseUrl = data)
        } else {
            MainPageSectionData(
                key = data.substring(0, separatorIndex),
                baseUrl = data.substring(separatorIndex + 1),
            )
        }
    }

    private fun getSectionDisplayTitle(sectionKey: String): String {
        return when (sectionKey) {
            "latest" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_LATEST_TITLE,
                latestEpisodesSectionName,
            )
            "calendar" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_CALENDAR_TITLE,
                calendarSectionName,
            )
            "ongoing" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_ONGOING_TITLE,
                ongoingSectionName,
            )
            "popular" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_POPULAR_TITLE,
                popularSectionName,
            )
            "best" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_BEST_TITLE,
                bestSectionName,
            )
            "upcoming" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_UPCOMING_TITLE,
                upcomingSectionName,
            )
            "random" -> AnimeUnityPlugin.getConfiguredSectionTitle(
                sharedPref,
                AnimeUnityPlugin.PREF_RANDOM_TITLE,
                randomSectionName,
            )
            else -> advancedSearchSectionName
        }
    }

    private fun buildSectionNamesList(): List<MainPageData> {
        val order = AnimeUnityPlugin.getConfiguredSectionOrder(sharedPref)
        val sections = buildList {
            if (AnimeUnityPlugin.isAdvancedSearchEnabled(sharedPref)) {
                add("advanced")
            }
            addAll(order.split(","))
        }

        return mainPageOf(
            *sections.mapNotNull { section ->
                when (section) {
                    "advanced" -> encodeMainPageSectionData("advanced", "$mainUrl/archive") to advancedSearchSectionName
                    "latest" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES)) {
                        encodeMainPageSectionData("latest", "$mainUrl/") to getSectionDisplayTitle("latest")
                    } else null
                    "calendar" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_CALENDAR)) {
                        encodeMainPageSectionData("calendar", "$mainUrl/calendar") to getSectionDisplayTitle("calendar")
                    } else null
                    "ongoing" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_ONGOING)) {
                        encodeMainPageSectionData("ongoing", "$mainUrl/archive") to getSectionDisplayTitle("ongoing")
                    } else null
                    "popular" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_POPULAR)) {
                        encodeMainPageSectionData("popular", "$mainUrl/top-anime") to getSectionDisplayTitle("popular")
                    } else null
                    "best" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_BEST)) {
                        encodeMainPageSectionData("best", "$mainUrl/top-anime") to getSectionDisplayTitle("best")
                    } else null
                    "upcoming" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_UPCOMING)) {
                        encodeMainPageSectionData("upcoming", "$mainUrl/archive") to getSectionDisplayTitle("upcoming")
                    } else null
                    "random" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_RANDOM)) {
                        encodeMainPageSectionData("random", "$mainUrl/random") to getSectionDisplayTitle("random")
                    } else null
                    else -> null
                }
            }.toTypedArray()
        )
    }

    private fun isSectionEnabled(prefKey: String): Boolean {
        return sharedPref?.getBoolean(prefKey, true) ?: true
    }

    private fun getSectionCount(sectionKey: String): Int {
        val (key, defaultCount) = when (sectionKey) {
            "advanced" -> AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT to
                AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT
            "latest" -> AnimeUnityPlugin.PREF_LATEST_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "calendar" -> AnimeUnityPlugin.PREF_CALENDAR_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "ongoing" -> AnimeUnityPlugin.PREF_ONGOING_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "popular" -> AnimeUnityPlugin.PREF_POPULAR_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "best" -> AnimeUnityPlugin.PREF_BEST_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "upcoming" -> AnimeUnityPlugin.PREF_UPCOMING_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            "random" -> AnimeUnityPlugin.PREF_RANDOM_COUNT to
                AnimeUnityPlugin.DEFAULT_SECTION_COUNT
            else -> return AnimeUnityPlugin.DEFAULT_SECTION_COUNT
        }
        return (sharedPref?.getInt(key, defaultCount)
            ?: defaultCount).coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun shouldShowDubSub(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true) ?: true
    }

    private fun shouldShowEpisodeNumber(): Boolean {
        return sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true) ?: true
    }

    private fun shouldUseUnifiedDubSubCards(): Boolean {
        return AnimeUnityPlugin.shouldUseUnifiedDubSubCards(sharedPref)
    }

    private fun withoutDubSuffix(title: String): String {
        return title.replace(" (ITA)", "").replace(" (ita)", "")
    }

    private fun getAnimeTitle(anime: Anime): String {
        return listOfNotNull(anime.titleIt, anime.titleEng, anime.title)
            .firstOrNull { it.isNotBlank() }
            ?: "Anime ${anime.id}"
    }

    private fun getAnimeTitle(anime: LatestEpisodeAnime): String {
        return listOfNotNull(anime.titleIt, anime.titleEng, anime.title)
            .firstOrNull { it.isNotBlank() }
            ?: "Anime ${anime.id}"
    }

    private fun isDubAnime(anime: Anime): Boolean {
        return anime.dub == 1 ||
            getAnimeTitle(anime).contains("(ITA)", ignoreCase = true) ||
            anime.slug.endsWith("-ita", ignoreCase = true)
    }

    private fun isDubAnime(anime: LatestEpisodeAnime): Boolean {
        return anime.dub == 1 ||
            getAnimeTitle(anime).contains("(ITA)", ignoreCase = true) ||
            anime.slug.endsWith("-ita", ignoreCase = true)
    }

    private fun Anime.contentKey(): String {
        return when {
            anilistId != null && anilistId > 0 -> "anilist:$anilistId"
            malId != null && malId > 0 -> "mal:$malId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun LatestEpisodeAnime.contentKey(): String {
        return when {
            anilistId != null && anilistId > 0 -> "anilist:$anilistId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun preferAnime(primary: Anime, candidate: Anime): Anime {
        val primaryIsDub = isDubAnime(primary)
        val candidateIsDub = isDubAnime(candidate)

        return when {
            primaryIsDub && !candidateIsDub -> candidate
            !primaryIsDub && candidateIsDub -> primary
            candidate.episodesCount > primary.episodesCount -> candidate
            else -> primary
        }
    }

    private fun preferAnime(primary: LatestEpisodeAnime, candidate: LatestEpisodeAnime): LatestEpisodeAnime {
        val primaryIsDub = isDubAnime(primary)
        val candidateIsDub = isDubAnime(candidate)

        return when {
            primaryIsDub && !candidateIsDub -> candidate
            !primaryIsDub && candidateIsDub -> primary
            candidate.episodesCount > primary.episodesCount -> candidate
            else -> primary
        }
    }

    private fun groupAnimeCards(animes: List<Anime>): List<GroupedAnimeCard> {
        if (animes.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return animes.map { anime ->
                GroupedAnimeCard(
                    anime = anime,
                    hasDub = isDubAnime(anime),
                )
            }
        }

        return animes
            .groupBy { it.contentKey() }
            .values
            .map { variants ->
                GroupedAnimeCard(
                    anime = variants.reduce { primary, candidate -> preferAnime(primary, candidate) },
                    hasDub = variants.any { isDubAnime(it) },
                )
            }
    }

    private fun groupLatestEpisodeCards(items: List<LatestEpisodeItem>): List<GroupedLatestEpisodeCard> {
        if (items.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return items.map { item ->
                GroupedLatestEpisodeCard(
                    anime = item.anime,
                    hasDub = isDubAnime(item.anime),
                    episodeNumber = item.number,
                )
            }
        }

        return items
            .groupBy { it.anime.contentKey() }
            .values
            .map { variants ->
                val latestEpisode = variants.maxWithOrNull(
                    compareBy<LatestEpisodeItem>(
                        { parseEpisodeSortValue(it.number) ?: Double.NEGATIVE_INFINITY },
                        { it.number }
                    )
                ) ?: variants.first()

                GroupedLatestEpisodeCard(
                    anime = variants.map { it.anime }.reduce { primary, candidate -> preferAnime(primary, candidate) },
                    hasDub = variants.any { isDubAnime(it.anime) },
                    episodeNumber = latestEpisode.number,
                )
            }
    }

    private fun getShowStatus(status: String?): ShowStatus? {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "terminato", "completed", "finished" -> ShowStatus.Completed
            "in corso", "ongoing", "airing", "releasing" -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun getStatusTag(status: String?): String? {
        val normalizedStatus = status?.trim().orEmpty()
        if (normalizedStatus.isBlank()) return null
        return if (getShowStatus(normalizedStatus) == null) "Stato: $normalizedStatus" else null
    }

    private fun buildDisplayTitle(title: String, episodeNumber: Int?): String {
        val baseTitle = withoutDubSuffix(title)

        return if (!shouldShowDubSub() && shouldShowEpisodeNumber() && episodeNumber != null) {
            "$baseTitle - Ep. $episodeNumber"
        } else {
            baseTitle
        }
    }

    private fun getMiniCardDubStatus(hasDub: Boolean): DubStatus {
        return if (hasDub) DubStatus.Dubbed else DubStatus.Subbed
    }

    private fun applyCardDisplayState(
        response: AnimeSearchResponse,
        dubStatus: DubStatus,
        poster: String?,
        episodeNumber: Int? = null,
    ) {
        if (shouldShowDubSub()) {
            if (shouldShowEpisodeNumber()) {
                response.addDubStatus(dubStatus, episodeNumber)
            } else {
                response.addDubStatus(dubStatus)
            }
        }

        response.addPoster(poster)
    }

    // ==================== GESTIONE SESSIONE & HEADERS ====================

    private fun getPageHeaders(): Map<String, String> {
        val currentHeaders = headers
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            put("Referer", "$mainUrl/")
            currentHeaders["Cookie"]?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
    }

    private fun getApiHeaders(): Map<String, String> {
        val currentHeaders = headers
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "application/json, text/plain, */*")
            put("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            put("X-Requested-With", "XMLHttpRequest")
            put("Referer", "$mainUrl/")
            currentHeaders["X-CSRF-Token"]?.takeIf(String::isNotBlank)?.let { put("X-CSRF-Token", it) }
            currentHeaders["X-XSRF-TOKEN"]?.takeIf(String::isNotBlank)?.let { put("X-XSRF-TOKEN", it) }
            currentHeaders["Cookie"]?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
    }

    private suspend fun ensureHeadersAndCookies(forceReset: Boolean = false) {
        val currentHeaders = headers
        val shouldRefreshHeaders = forceReset ||
            currentHeaders["Cookie"].isNullOrBlank()

        if (shouldRefreshHeaders) {
            setupHeadersAndCookies()
        }
    }

    private suspend fun setupHeadersAndCookies() {
        val configuredBase = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        val initialBase = resolvedBaseUrl ?: configuredBase
        val response = runCatching {
            app.get(
                "$initialBase/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            )
        }.getOrNull() ?: return

        runCatching {
            val finalHttpUrl = response.url.toHttpUrl()
            resolvedBaseUrl = "${finalHttpUrl.scheme}://${finalHttpUrl.host}"
        }

        val activeBase = resolvedBaseUrl ?: initialBase
        val metaCsrf = response.document.head().select("meta[name=csrf-token]").attr("content").trim()
        val rawXsrf = response.cookies["XSRF-TOKEN"].orEmpty()
        val decodedXsrf = if (rawXsrf.isNotBlank()) {
            runCatching { URLDecoder.decode(rawXsrf, "UTF-8") }.getOrDefault(rawXsrf)
        } else {
            ""
        }
        val csrfToken = metaCsrf.ifBlank { decodedXsrf }

        val cookies = response.cookies
            .filter { (k, v) -> k.isNotBlank() && v.isNotBlank() && v != "null" }
            .entries
            .joinToString("; ") { "${it.key}=${it.value}" }

        Log.e(LOG_TAG, "Session setup -> url=${response.url} code=${response.code} cookies=${response.cookies.keys}")

        val newHeaders = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json;charset=utf-8",
            "Origin" to activeBase,
            "Referer" to "$activeBase/"
        )
        if (csrfToken.isNotBlank()) newHeaders["X-CSRF-Token"] = csrfToken
        if (decodedXsrf.isNotBlank()) newHeaders["X-XSRF-TOKEN"] = decodedXsrf
        if (cookies.isNotBlank()) newHeaders["Cookie"] = cookies

        headers = newHeaders
    }

    // ==================== PARSER UNIVERSALE NUOVO SITO (INERTIA / API / DOM) ====================

    private fun JsonNode?.asTextOrNull(vararg keys: String): String? {
        if (this == null || this.isNull) return null
        for (key in keys) {
            val child = this.get(key)
            if (child != null && !child.isNull && child.isValueNode) {
                val text = child.asText("").trim()
                if (text.isNotBlank() && text != "null") return text
            }
        }
        return null
    }

    private fun JsonNode?.asIntOrNull(vararg keys: String): Int? {
        if (this == null || this.isNull) return null
        for (key in keys) {
            val child = this.get(key)
            if (child != null && !child.isNull) {
                if (child.isNumber) return child.asInt()
                val parsed = child.asText("").trim().toIntOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private fun JsonNode?.asBoolInt(vararg keys: String): Int {
        if (this == null || this.isNull) return 0
        for (key in keys) {
            val child = this.get(key)
            if (child != null && !child.isNull) {
                if (child.isBoolean) return if (child.asBoolean()) 1 else 0
                if (child.isNumber) return if (child.asInt() != 0) 1 else 0
                val text = child.asText("").trim().lowercase(Locale.ROOT)
                if (text == "true" || text == "1" || text == "dub" || text == "ita") return 1
            }
        }
        return 0
    }

    private fun extractInertiaProps(doc: Document): JsonNode? {
        val dataPage = doc.selectFirst("#app[data-page], [data-page]")
            ?.attr("data-page")
            ?.takeIf(String::isNotBlank)
            ?: return null

        return runCatching {
            val root = parseJson<JsonNode>(dataPage)
            root.get("props") ?: root
        }.onFailure {
            Log.e(LOG_TAG, "Failed to parse Inertia data-page: ${it.message}")
        }.getOrNull()
    }

    private fun findFirstArrayNode(node: JsonNode?, vararg preferredKeys: String): JsonNode? {
        if (node == null || node.isNull) return null
        if (node.isArray) return node

        for (key in preferredKeys) {
            val child = node.get(key) ?: continue
            if (child.isArray) return child
            if (child.isObject) {
                val nestedData = child.get("data") ?: child.get("records") ?: child.get("items") ?: child.get("episodes")
                if (nestedData != null && nestedData.isArray) return nestedData
            }
        }

        val defaultKeys = listOf("data", "records", "episodes", "animes", "titles", "items", "results", "list", "calendar", "topAnimes", "latestEpisodes")
        for (key in defaultKeys) {
            val child = node.get(key) ?: continue
            if (child.isArray) return child
            if (child.isObject) {
                val nested = child.get("data") ?: child.get("records") ?: child.get("items")
                if (nested != null && nested.isArray) return nested
            }
        }

        val fields = node.fields()
        while (fields.hasNext()) {
            val (_, value) = fields.next()
            if (value.isArray && value.size() > 0 && value[0].isObject) {
                return value
            }
            if (value.isObject) {
                val nested = value.get("data")
                if (nested != null && nested.isArray && nested.size() > 0) return nested
            }
        }

        return null
    }

    private fun parseAnimeFromNode(node: JsonNode): Anime? {
        val target = if (node.has("anime") && node.get("anime").isObject) node.get("anime") else node
        val id = target.asIntOrNull("id", "anime_id", "animeId") ?: return null
        val slug = target.asTextOrNull("slug") ?: ""
        val title = target.asTextOrNull("title", "name", "title_eng", "title_it")
        val titleEng = target.asTextOrNull("title_eng", "titleEng", "english_title")
        val titleIt = target.asTextOrNull("title_it", "titleIt", "italian_title")

        if (title.isNullOrBlank() && titleEng.isNullOrBlank() && titleIt.isNullOrBlank()) return null

        val imageUrl = target.asTextOrNull(
            "imageurl",
            "image_url",
            "imageUrl",
            "poster",
            "poster_url",
            "cover_image",
            "image",
            "thumb"
        )
        val coverUrl = target.asTextOrNull(
            "cover",
            "banner",
            "banner_url",
            "imageurl_cover",
            "cover_url"
        )
        val date = target.asTextOrNull("date", "year", "release_year", "season_year").orEmpty()
        val status = target.asTextOrNull("status", "state").orEmpty()
        val type = target.asTextOrNull("type", "format", "media_type") ?: "TV"
        val dub = target.asBoolInt("dub", "is_dub", "dubbed", "has_dub")
        val episodesCount = target.asIntOrNull("episodes_count", "episodesCount", "total_episodes") ?: 0
        val episodesLength = target.asIntOrNull("episodes_length", "episodesLength", "duration") ?: 24
        val anilistId = target.asIntOrNull("anilist_id", "anilistId")
        val malId = target.asIntOrNull("mal_id", "malId")
        val day = target.asTextOrNull("day", "broadcast_day", "release_day") ?: node.asTextOrNull("day")

        val genresList = mutableListOf<Genre>()
        val genresNode = target.get("genres")
        if (genresNode != null && genresNode.isArray) {
            genresNode.forEachIndexed { idx, g ->
                if (g.isObject) {
                    val gName = g.asTextOrNull("name", "title")
                    if (!gName.isNullOrBlank()) {
                        genresList.add(Genre(id = g.asIntOrNull("id") ?: idx, name = gName))
                    }
                } else if (g.isTextual) {
                    genresList.add(Genre(id = idx, name = g.asText()))
                }
            }
        }

        val episodesList = mutableListOf<Episode>()
        val episodesNode = target.get("episodes") ?: target.get("videos")
        if (episodesNode != null && episodesNode.isArray) {
            episodesNode.forEach { epNode ->
                parseEpisodeFromNode(epNode, id)?.let(episodesList::add)
            }
        }

        return Anime(
            id = id,
            title = title ?: titleEng ?: titleIt,
            imageUrl = imageUrl,
            date = date,
            episodesCount = episodesCount,
            episodesLength = episodesLength,
            status = status,
            type = type,
            slug = slug,
            titleEng = titleEng,
            day = day,
            dub = dub,
            cover = coverUrl,
            anilistId = anilistId,
            titleIt = titleIt,
            malId = malId,
            episodes = episodesList,
            genres = genresList
        )
    }

    private fun parseEpisodeFromNode(node: JsonNode, fallbackAnimeId: Int = 0): Episode? {
        val id = node.asIntOrNull("id", "video_id", "episode_id") ?: return null
        val number = node.asTextOrNull("number", "episode_number", "ep", "num") ?: "1"
        val link = node.asTextOrNull("link", "embed_url", "embedUrl", "url", "scws_url")
        val scwsId = node.asIntOrNull("scws_id", "scwsId")

        return Episode(
            id = id,
            animeId = node.asIntOrNull("anime_id", "animeId") ?: fallbackAnimeId,
            userId = null,
            number = number,
            link = link,
            visite = 0,
            hidden = 0,
            isPublic = 1,
            scwsId = scwsId,
            fileName = node.asTextOrNull("file_name", "fileName")
        )
    }

    private fun parseLatestEpisodeItemFromNode(node: JsonNode): LatestEpisodeItem? {
        val animeNode = if (node.has("anime") && node.get("anime").isObject) {
            node.get("anime")
        } else {
            node
        }
        val anime = parseAnimeFromNode(animeNode) ?: return null
        val epId = node.asIntOrNull("id", "episode_id", "video_id") ?: anime.id
        val epNumber = node.asTextOrNull("number", "episode_number", "ep", "latest_episode")
            ?: anime.episodes?.lastOrNull()?.number
            ?: (if (anime.episodesCount > 0) anime.episodesCount.toString() else "1")

        return LatestEpisodeItem(
            id = epId,
            animeId = anime.id,
            userId = null,
            number = epNumber.removePrefix("EP ").removePrefix("Ep ").trim(),
            anime = LatestEpisodeAnime(
                id = anime.id,
                title = anime.title,
                imageUrl = anime.imageUrl,
                episodesCount = anime.episodesCount,
                type = anime.type,
                slug = anime.slug,
                titleEng = anime.titleEng,
                dub = anime.dub,
                cover = anime.cover,
                anilistId = anime.anilistId,
                titleIt = anime.titleIt
            )
        )
    }

    private fun parseAnimesFromHtmlDom(doc: Document): List<Anime> {
        val results = linkedMapOf<Int, Anime>()
        doc.select("a[href*=/anime/]").forEach { link ->
            val href = link.attr("href")
            val match = Regex("""/anime/(\d+)-([a-zA-Z0-9-]+)""").find(href) ?: return@forEach
            val id = match.groupValues[1].toIntOrNull() ?: return@forEach
            val slug = match.groupValues[2]

            val card = link.closest(".group\\/card, .group") ?: link.parent()
            val img = card?.selectFirst("img") ?: link.selectFirst("img")
            val title = listOfNotNull(
                img?.attr("alt")?.trim(),
                link.text().trim(),
                card?.selectFirst("a[href*=/anime/]:not(:has(img))")?.text()?.trim()
            ).firstOrNull { it.isNotBlank() && !it.startsWith("EP ", ignoreCase = true) }
                ?: return@forEach

            val rawImg = img?.attr("src")?.takeIf(String::isNotBlank)
            val cardText = card?.text().orEmpty()
            val isDub = cardText.contains("DUB", ignoreCase = true) ||
                title.contains("(ITA)", ignoreCase = true) ||
                slug.endsWith("-ita")
            val isMovie = cardText.contains("FILM", ignoreCase = true) ||
                cardText.contains("Movie", ignoreCase = true)

            results.putIfAbsent(
                id,
                Anime(
                    id = id,
                    title = title,
                    imageUrl = rawImg,
                    date = "",
                    episodesCount = if (isMovie) 1 else 0,
                    episodesLength = 24,
                    status = "",
                    type = if (isMovie) "Movie" else "TV",
                    slug = slug,
                    titleEng = title,
                    day = null,
                    dub = if (isDub) 1 else 0,
                    cover = null,
                    anilistId = null,
                    titleIt = title,
                    malId = null,
                    episodes = emptyList(),
                    genres = emptyList()
                )
            )
        }
        return results.values.toList()
    }

    // ==================== IMMAGINI & URL ====================

    private fun getImageCdnHost(): String {
        val host = mainUrl.toHttpUrl().host
        return when {
            host == "animeunity.co" -> "img.animeunity.co"
            host.startsWith("www.") -> host.replaceFirst("www.", "img.")
            host.startsWith("img.") -> host
            else -> "img.$host"
        }
    }

    private suspend fun getImage(imageUrl: String?, anilistId: Int?): String? {
        if (!imageUrl.isNullOrBlank()) {
            val trimmed = imageUrl.trim()
            when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> return trimmed
                trimmed.startsWith("//") -> return "https:$trimmed"
                trimmed.startsWith("/storage/") || trimmed.startsWith("storage/") -> {
                    return "$mainUrl/${trimmed.trimStart('/')}"
                }
                trimmed.startsWith("/") -> return "$mainUrl$trimmed"
                else -> {
                    val fileName = trimmed.substringAfterLast("/")
                    return "https://${getImageCdnHost()}/anime/$fileName"
                }
            }
        }
        return anilistId?.let { getAnilistPoster(it) }
    }

    private fun getBanner(imageUrl: String): String {
        val trimmed = imageUrl.trim()
        if (trimmed.isNotEmpty()) {
            when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> return trimmed
                trimmed.startsWith("/storage/") || trimmed.startsWith("storage/") -> {
                    return "$mainUrl/${trimmed.trimStart('/')}"
                }
                trimmed.startsWith("/") -> return "$mainUrl$trimmed"
                else -> {
                    val fileName = trimmed.substringAfterLast("/")
                    return "https://${getImageCdnHost()}/anime/$fileName"
                }
            }
        }
        return imageUrl
    }

    private fun getAnimeUrl(anime: Anime): String {
        return "$mainUrl/anime/${anime.id}-${anime.slug}"
    }

    private fun getEpisodeUrl(anime: Anime, episode: Episode): String {
        return "${getAnimeUrl(anime)}/${episode.id}"
    }

    private fun parseEpisodeSortValue(number: String?): Double? {
        return number
            ?.trim()
            ?.replace(',', '.')
            ?.toDoubleOrNull()
    }

    private fun buildEpisodeDisplayName(number: String): String {
        return "Episodio $number"
    }

    private fun buildPlayerSourceOptions(playbackData: EpisodePlaybackData): List<PlayerSourceOption> {
        val orderedSources = mutableListOf<PlayerSourceOption>()
        val seenUrls = linkedSetOf<String>()

        fun addSource(url: String?, label: String) {
            val normalizedUrl = url?.takeIf(String::isNotBlank) ?: return
            if (seenUrls.add(normalizedUrl)) {
                orderedSources += PlayerSourceOption(label = label, url = normalizedUrl)
            }
        }

        when (playbackData.preferredUrl) {
            playbackData.dubUrl -> {
                addSource(playbackData.dubUrl, "[DUB]")
                addSource(playbackData.subUrl, "[SUB]")
            }
            playbackData.subUrl -> {
                addSource(playbackData.subUrl, "[SUB]")
                addSource(playbackData.dubUrl, "[DUB]")
            }
            else -> {
                addSource(playbackData.preferredUrl, "[SOURCE]")
                addSource(playbackData.dubUrl, "[DUB]")
                addSource(playbackData.subUrl, "[SUB]")
            }
        }

        return orderedSources
    }

    private fun buildEpisodeSourceMap(anime: Anime?, episodes: List<Episode>): LinkedHashMap<String, EpisodeSource> {
        val sourceAnime = anime ?: return linkedMapOf()
        val sourceMap = linkedMapOf<String, EpisodeSource>()

        episodes.forEach { episode ->
            val rawNumber = episode.number.trim()
            sourceMap.putIfAbsent(
                rawNumber,
                EpisodeSource(
                    number = rawNumber,
                    url = getEpisodeUrl(sourceAnime, episode),
                    directEmbedUrl = episode.link?.takeIf { it.startsWith("http") }
                )
            )
        }

        return sourceMap
    }

    private fun buildMergedEpisodes(
        primaryEpisodes: LinkedHashMap<String, EpisodeSource>,
        fallbackEpisodes: LinkedHashMap<String, EpisodeSource>,
        subEpisodes: LinkedHashMap<String, EpisodeSource>,
        dubEpisodes: LinkedHashMap<String, EpisodeSource>,
        fallbackNamePrefix: String? = null,
    ): List<com.lagradost.cloudstream3.Episode> {
        return (primaryEpisodes.keys + fallbackEpisodes.keys)
            .distinct()
            .sortedWith(
                compareBy<String>(
                    { parseEpisodeSortValue(it) ?: Double.POSITIVE_INFINITY },
                    { it }
                )
            )
            .map { episodeNumber ->
                val source = primaryEpisodes[episodeNumber] ?: fallbackEpisodes[episodeNumber]!!
                val isFallbackEpisode = primaryEpisodes[episodeNumber] == null
                val playbackData = EpisodePlaybackData(
                    preferredUrl = source.url,
                    subUrl = subEpisodes[episodeNumber]?.url,
                    dubUrl = dubEpisodes[episodeNumber]?.url,
                    embedUrl = source.directEmbedUrl
                )
                newEpisode(playbackData) {
                    this.episode = source.number.toIntOrNull()
                    if (isFallbackEpisode && fallbackNamePrefix != null) {
                        this.name = "$fallbackNamePrefix${buildEpisodeDisplayName(source.number)}"
                    } else if (this.episode == null) {
                        this.name = buildEpisodeDisplayName(source.number)
                    }
                }
            }
    }

    private suspend fun searchResponseBuilder(objectList: List<Anime>, episodeNumber: Int? = null): List<SearchResponse> {
        return groupAnimeCards(objectList).amap { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, episodeNumber),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type.equals("TV", ignoreCase = true) -> TvType.Anime
                    anime.type.equals("Movie", ignoreCase = true) ||
                        anime.type.equals("Film", ignoreCase = true) ||
                        anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    dubStatus = getMiniCardDubStatus(entry.hasDub),
                    poster = poster,
                    episodeNumber = episodeNumber
                )
            }
        }
    }

    private suspend fun latestEpisodesResponseBuilder(objectList: List<LatestEpisodeItem>): List<SearchResponse> {
        return groupLatestEpisodeCards(objectList).amap { entry ->
            val anime = entry.anime
            val title = getAnimeTitle(anime)
            val poster = getImage(anime.imageUrl, anime.anilistId)

            newAnimeSearchResponse(
                name = buildDisplayTitle(title, entry.episodeNumber.toIntOrNull()),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = when {
                    anime.type.equals("TV", ignoreCase = true) -> TvType.Anime
                    anime.type.equals("Movie", ignoreCase = true) ||
                        anime.type.equals("Film", ignoreCase = true) ||
                        anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    dubStatus = getMiniCardDubStatus(entry.hasDub),
                    poster = poster,
                    episodeNumber = entry.episodeNumber.toIntOrNull()
                )
            }
        }
    }

    private fun buildDebugDiagnosticCard(sectionKey: String, debugInfo: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(debugInfo.take(1500), "UTF-8")
        return listOf(
            newAnimeSearchResponse(
                name = "[DEBUG $sectionKey] Clicca per dettagli",
                url = "$mainUrl/debug-info?msg=$encoded",
                type = TvType.Anime
            )
        )
    }

    // ==================== CHIAMATE API NUOVE & FALLBACK ====================

    private suspend fun fetchAnimesFromApiOrInertia(
        apiPath: String,
        pagePath: String,
        queryParams: Map<String, String> = emptyMap(),
        preferredKeys: Array<String> = emptyArray(),
    ): Pair<List<Anime>, String> {
        ensureHeadersAndCookies()

        val apiUrlBuilder = "$mainUrl$apiPath".toHttpUrlOrNull()?.newBuilder()
        queryParams.forEach { (k, v) ->
            if (v.isNotBlank() && v != "false") apiUrlBuilder?.addQueryParameter(k, v)
        }
        val fullApiUrl = apiUrlBuilder?.build()?.toString() ?: "$mainUrl$apiPath"

        val apiResp = runCatching { app.get(fullApiUrl, headers = getApiHeaders()) }.getOrNull()
        val apiText = apiResp?.text?.trim().orEmpty()
        Log.e(LOG_TAG, "GET $fullApiUrl -> code=${apiResp?.code} body=${apiText.take(250)}")

        if (apiText.startsWith("{") || apiText.startsWith("[")) {
            val rootNode = runCatching { parseJson<JsonNode>(apiText) }.getOrNull()
            val arrayNode = findFirstArrayNode(rootNode, *preferredKeys)
            if (arrayNode != null) {
                val parsed = arrayNode.mapNotNull(::parseAnimeFromNode)
                if (parsed.isNotEmpty()) {
                    return parsed to "OK_API"
                }
            }
        }

        // Fallback 2: Pagina Inertia (es. /archive, /top-anime, /calendar)
        val pageUrlBuilder = "$mainUrl$pagePath".toHttpUrlOrNull()?.newBuilder()
        queryParams.forEach { (k, v) ->
            if (v.isNotBlank() && v != "false") pageUrlBuilder?.addQueryParameter(k, v)
        }
        val fullPageUrl = pageUrlBuilder?.build()?.toString() ?: "$mainUrl$pagePath"

        val pageResp = runCatching { app.get(fullPageUrl, headers = getPageHeaders()) }.getOrNull()
        val doc = pageResp?.document
        Log.e(LOG_TAG, "GET Page $fullPageUrl -> code=${pageResp?.code} title=${doc?.title()}")

        if (doc != null) {
            val props = extractInertiaProps(doc)
            if (props != null) {
                val arrayNode = findFirstArrayNode(props, *preferredKeys)
                if (arrayNode != null) {
                    val parsed = arrayNode.mapNotNull(::parseAnimeFromNode)
                    if (parsed.isNotEmpty()) {
                        return parsed to "OK_INERTIA"
                    }
                }
            }

            val domAnimes = parseAnimesFromHtmlDom(doc)
            if (domAnimes.isNotEmpty()) {
                return domAnimes to "OK_DOM"
            }
        }

        val diag = "API($fullApiUrl): HTTP ${apiResp?.code} -> ${apiText.take(300)}\n" +
            "PAGE($fullPageUrl): HTTP ${pageResp?.code} title=${doc?.title()} hasDataPage=${doc?.selectFirst("#app[data-page]") != null}"
        return emptyList<Anime>() to diag
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionData = decodeMainPageSectionData(request.data)
        val sectionCount = getSectionCount(sectionData.key)

        if (sectionData.key == "latest") {
            return getLatestEpisodesMainPage(page, request.name, sectionCount)
        }
        if (sectionData.key == "calendar") {
            return getCalendarMainPage(page, request.name, sectionCount)
        }
        if (sectionData.key == "random") {
            return getRandomMainPage(page, request.name, sectionCount)
        }

        val (apiPath, pagePath, params) = when (sectionData.key) {
            "popular" -> Triple(
                "/api/top-anime",
                "/top-anime",
                mapOf("period" to "day", "page" to page.toString())
            )
            "best" -> Triple(
                "/api/top-anime",
                "/top-anime",
                mapOf("period" to "month", "sort_by" to "score", "page" to page.toString())
            )
            "ongoing" -> Triple(
                "/api/archive",
                "/archive",
                mapOf("status" to "ongoing", "sort_by" to "popularity", "page" to page.toString())
            )
            "upcoming" -> Triple(
                "/api/archive",
                "/archive",
                mapOf("status" to "upcoming", "sort_by" to "latest", "page" to page.toString())
            )
            "advanced" -> {
                val cfg = AnimeUnityPlugin.getAdvancedSearchConfig(sharedPref)
                val advParams = buildMap {
                    put("page", page.toString())
                    if (cfg.title.isNotBlank()) put("q", cfg.title)
                    if (!cfg.year.isNullOrBlank()) put("year", cfg.year)
                    if (!cfg.type.isNullOrBlank()) put("type", cfg.type)
                    if (!cfg.status.isNullOrBlank()) put("status", cfg.status)
                    if (!cfg.season.isNullOrBlank()) put("season", cfg.season)
                    if (cfg.genre != null) put("genres", cfg.genre.id.toString())
                }
                Triple("/api/archive", "/archive", advParams)
            }
            else -> Triple("/api/archive", "/archive", mapOf("page" to page.toString()))
        }

        val (titles, diag) = fetchAnimesFromApiOrInertia(apiPath, pagePath, params)
        val cards = if (titles.isNotEmpty()) {
            searchResponseBuilder(titles.take(sectionCount))
        } else if (page == 1) {
            buildDebugDiagnosticCard(sectionData.key, diag)
        } else {
            emptyList()
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = cards,
                isHorizontalImages = false
            ),
            titles.size >= 15 && page < 5
        )
    }

    private suspend fun getLatestEpisodesMainPage(
        page: Int,
        sectionTitle: String,
        sectionCount: Int,
    ): HomePageResponse {
        ensureHeadersAndCookies()

        val items = mutableListOf<LatestEpisodeItem>()
        var diagInfo = ""

        // 1. Prova nuova API GET /api/home/latest-episodes
        val apiUrl = "$mainUrl/api/home/latest-episodes?page=$page"
        val apiResp = runCatching { app.get(apiUrl, headers = getApiHeaders()) }.getOrNull()
        val apiText = apiResp?.text?.trim().orEmpty()
        Log.e(LOG_TAG, "GET $apiUrl -> code=${apiResp?.code} body=${apiText.take(250)}")

        if (apiText.startsWith("{") || apiText.startsWith("[")) {
            val root = runCatching { parseJson<JsonNode>(apiText) }.getOrNull()
            val arr = findFirstArrayNode(root, "episodes", "latestEpisodes", "data", "items")
            if (arr != null) {
                items.addAll(arr.mapNotNull(::parseLatestEpisodeItemFromNode))
            }
        }

        // 2. Fallback: pagina Home Inertia / Vecchia struttura / DOM
        if (items.isEmpty() && page == 1) {
            val homeResp = runCatching { app.get("$mainUrl/", headers = getPageHeaders()) }.getOrNull()
            val doc = homeResp?.document
            if (doc != null) {
                val props = extractInertiaProps(doc)
                if (props != null) {
                    val arr = findFirstArrayNode(props, "latestEpisodes", "latest_episodes", "episodes", "data")
                    if (arr != null) {
                        items.addAll(arr.mapNotNull(::parseLatestEpisodeItemFromNode))
                    }
                }

                if (items.isEmpty()) {
                    val legacyJson = doc.selectFirst("#ultimi-episodi layout-items")?.attr("items-json").orEmpty()
                    if (legacyJson.isNotBlank()) {
                        val legacyItems = runCatching { parseJson<LatestEpisodesPage>(legacyJson).episodes }.getOrDefault(emptyList())
                        items.addAll(legacyItems)
                    }
                }

                if (items.isEmpty()) {
                    val domAnimes = parseAnimesFromHtmlDom(doc)
                    items.addAll(
                        domAnimes.map { a ->
                            LatestEpisodeItem(
                                id = a.id,
                                animeId = a.id,
                                userId = null,
                                number = "1",
                                anime = LatestEpisodeAnime(
                                    id = a.id,
                                    title = a.title,
                                    imageUrl = a.imageUrl,
                                    episodesCount = a.episodesCount,
                                    type = a.type,
                                    slug = a.slug,
                                    titleEng = a.titleEng,
                                    dub = a.dub,
                                    cover = a.cover,
                                    anilistId = a.anilistId,
                                    titleIt = a.titleIt
                                )
                            )
                        }
                    )
                }
            }
            diagInfo = "API($apiUrl): HTTP ${apiResp?.code} -> ${apiText.take(250)}\n" +
                "HOME($mainUrl/): HTTP ${homeResp?.code} title=${doc?.title()} hasDataPage=${doc?.selectFirst("#app[data-page]") != null}"
        }

        val cards = if (items.isNotEmpty()) {
            latestEpisodesResponseBuilder(items.take(sectionCount))
        } else if (page == 1) {
            buildDebugDiagnosticCard("latest", diagInfo)
        } else {
            emptyList()
        }

        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = cards,
                isHorizontalImages = false
            ),
            items.isNotEmpty() && page < 5
        )
    }

    private suspend fun getCalendarMainPage(
        page: Int,
        sectionTitle: String,
        sectionCount: Int,
    ): HomePageResponse {
        val currentDay = getCurrentItalianDayName()
        val calendarTitle = "$sectionTitle ($currentDay)"
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = calendarTitle, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val (animes, diag) = fetchAnimesFromApiOrInertia(
            apiPath = "/api/calendar",
            pagePath = "/calendar",
            preferredKeys = arrayOf("calendar", "animes", "episodes", "data", "items")
        )

        val filtered = animes.filter {
            it.day.isNullOrBlank() || normalizeDayName(it.day).startsWith(normalizeDayName(currentDay).take(3))
        }.ifEmpty { animes }.take(sectionCount)

        val cards = if (filtered.isNotEmpty()) {
            searchResponseBuilder(filtered)
        } else {
            buildDebugDiagnosticCard("calendar", diag)
        }

        return newHomePageResponse(
            HomePageList(
                name = calendarTitle,
                list = cards,
                isHorizontalImages = false
            ),
            false
        )
    }

    private suspend fun getRandomMainPage(
        page: Int,
        sectionTitle: String,
        sectionCount: Int,
    ): HomePageResponse {
        val (animes, diag) = fetchAnimesFromApiOrInertia(
            apiPath = "/api/archive",
            pagePath = "/archive",
            queryParams = mapOf("page" to page.toString(), "sort_by" to "random")
        )

        val list = if (animes.isNotEmpty()) {
            searchResponseBuilder(animes.shuffled().take(sectionCount))
        } else if (page == 1) {
            buildDebugDiagnosticCard("random", diag)
        } else {
            emptyList()
        }

        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = list,
                isHorizontalImages = false
            ),
            page < 5 && animes.isNotEmpty()
        )
    }

    private fun getCurrentItalianDayName(): String {
        val formatter = SimpleDateFormat("EEEE", Locale.ITALIAN)
        return formatter.format(Date()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString()
        }
    }

    private fun normalizeDayName(dayName: String?): String {
        return Normalizer.normalize(dayName.orEmpty(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

    // ==================== RICERCA ====================

    override suspend fun search(query: String): List<SearchResponse> {
        ensureHeadersAndCookies()

        val (searchAnimes, _) = fetchAnimesFromApiOrInertia(
            apiPath = "/api/search",
            pagePath = "/archive",
            queryParams = mapOf("q" to query, "title" to query, "query" to query)
        )

        if (searchAnimes.isNotEmpty()) {
            return searchResponseBuilder(searchAnimes)
        }

        val (archiveAnimes, _) = fetchAnimesFromApiOrInertia(
            apiPath = "/api/archive",
            pagePath = "/archive",
            queryParams = mapOf("q" to query, "title" to query, "search" to query)
        )

        return searchResponseBuilder(archiveAnimes)
    }

    // ==================== SCHEDA ANIME & EPISODI ====================

    private suspend fun fetchEpisodesFromNewApi(animeId: Int): List<Episode> {
        val apiUrl = "$mainUrl/api/anime/$animeId/episodes"
        val resp = runCatching { app.get(apiUrl, headers = getApiHeaders()) }.getOrNull() ?: return emptyList()
        val text = resp.text.trim()
        Log.e(LOG_TAG, "GET $apiUrl -> code=${resp.code} body=${text.take(250)}")

        if (!text.startsWith("{") && !text.startsWith("[")) return emptyList()
        val root = runCatching { parseJson<JsonNode>(text) }.getOrNull() ?: return emptyList()
        val arr = findFirstArrayNode(root, "episodes", "data", "videos", "items") ?: return emptyList()
        return arr.mapNotNull { parseEpisodeFromNode(it, animeId) }
    }

    private suspend fun parseAnimePageData(animePage: Document, pageUrl: String): AnimePageData {
        // 1. Prova estrazione da Inertia data-page
        val props = extractInertiaProps(animePage)
        if (props != null) {
            val animeNode = props.get("anime") ?: props.get("media") ?: props.get("data") ?: props
            val parsedAnime = parseAnimeFromNode(animeNode)
            if (parsedAnime != null) {
                val propsEpisodesNode = findFirstArrayNode(props, "episodes", "videos")
                    ?: findFirstArrayNode(animeNode, "episodes", "videos")
                val propsEpisodes = propsEpisodesNode
                    ?.mapNotNull { parseEpisodeFromNode(it, parsedAnime.id) }
                    .orEmpty()

                val allEpisodes = propsEpisodes.ifEmpty { fetchEpisodesFromNewApi(parsedAnime.id) }

                val relatedNode = findFirstArrayNode(props, "related", "relatedAnimes", "related_animes", "recommendations")
                    ?: findFirstArrayNode(animeNode, "related", "relatedAnimes", "related_animes")
                val relatedList = relatedNode?.mapNotNull(::parseAnimeFromNode).orEmpty()

                return AnimePageData(
                    anime = parsedAnime,
                    relatedAnime = relatedList,
                    episodes = allEpisodes
                )
            }
        }

        // 2. Fallback vecchio tag <video-player>
        val videoPlayer = animePage.selectFirst("video-player")
        if (videoPlayer != null && videoPlayer.attr("anime").isNotBlank()) {
            val anime = parseJson<Anime>(videoPlayer.attr("anime"))
            val initialEpisodes = runCatching { parseJson<List<Episode>>(videoPlayer.attr("episodes")) }.getOrDefault(emptyList())
            return AnimePageData(
                anime = anime,
                relatedAnime = emptyList(),
                episodes = initialEpisodes
            )
        }

        // 3. Fallback da URL / DOM
        val match = Regex("""/anime/(\d+)-([a-zA-Z0-9-]+)""").find(pageUrl)
        val fallbackId = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val fallbackSlug = match?.groupValues?.get(2).orEmpty()
        val apiEpisodes = if (fallbackId > 0) fetchEpisodesFromNewApi(fallbackId) else emptyList()

        return AnimePageData(
            anime = Anime(
                id = fallbackId,
                title = animePage.title().removeSuffix(" - AnimeUnity").trim().ifBlank { fallbackSlug },
                slug = fallbackSlug,
                episodes = apiEpisodes
            ),
            relatedAnime = parseAnimesFromHtmlDom(animePage),
            episodes = apiEpisodes
        )
    }

    private suspend fun fetchAnimePageData(url: String): AnimePageData {
        val response = app.get(url, headers = getPageHeaders())
        return parseAnimePageData(response.document, url)
    }

    private suspend fun findAnimeVariants(currentAnime: Anime): List<Anime> {
        val searchTitle = withoutDubSuffix(getAnimeTitle(currentAnime))
        val (results, _) = fetchAnimesFromApiOrInertia(
            apiPath = "/api/search",
            pagePath = "/archive",
            queryParams = mapOf("q" to searchTitle, "title" to searchTitle)
        )

        return (results + currentAnime)
            .filter { it.contentKey() == currentAnime.contentKey() }
            .distinctBy(Anime::id)
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/debug-info")) {
            val msg = url.substringAfter("msg=", "").let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            return newAnimeLoadResponse(
                name = "Diagnostica AnimeUnity",
                url = url,
                type = TvType.Anime
            ) {
                this.plot = msg
            }
        }

        ensureHeadersAndCookies()
        val targetUrl = if (url.startsWith("http")) {
            val path = url.toHttpUrl().encodedPath
            "$mainUrl$path"
        } else {
            "$mainUrl/${url.trimStart('/')}"
        }

        val currentPageData = fetchAnimePageData(targetUrl)
        val currentAnime = currentPageData.anime
        val shouldMergeVariants = shouldUseUnifiedDubSubCards()
        val variants = if (shouldMergeVariants) findAnimeVariants(currentAnime) else listOf(currentAnime)

        val subAnime = variants.firstOrNull { !isDubAnime(it) } ?: currentAnime.takeIf { !isDubAnime(it) }
        val dubAnime = variants.firstOrNull { isDubAnime(it) } ?: currentAnime.takeIf { isDubAnime(it) }

        val subPageData = when {
            subAnime == null -> null
            subAnime.id == currentAnime.id -> currentPageData
            !shouldMergeVariants -> null
            else -> runCatching { fetchAnimePageData(getAnimeUrl(subAnime)) }.getOrNull()
        }

        val dubPageData = when {
            dubAnime == null -> null
            dubAnime.id == currentAnime.id -> currentPageData
            !shouldMergeVariants -> null
            else -> runCatching { fetchAnimePageData(getAnimeUrl(dubAnime)) }.getOrNull()
        }

        val primaryAnime = subPageData?.anime ?: dubPageData?.anime ?: currentAnime
        val title = getAnimeTitle(primaryAnime)
        val currentNormalizedTitle = normalizeJikanTitle(withoutDubSuffix(title))
        val excludedIds = setOfNotNull(currentAnime.id, subAnime?.id, dubAnime?.id)

        val filteredCorrelated = currentPageData.relatedAnime.filterNot { related ->
            related.id in excludedIds ||
                (shouldMergeVariants &&
                    normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(related))) == currentNormalizedTitle)
        }

        val relatedAnimes = groupAnimeCards(filteredCorrelated)
            .sortedBy { it.anime.date.toIntOrNull() ?: Int.MAX_VALUE }
            .amap { entry ->
                val anime = entry.anime
                val relatedTitle = getAnimeTitle(anime)
                val poster = getImage(anime.imageUrl, anime.anilistId)
                newAnimeSearchResponse(
                    name = withoutDubSuffix(relatedTitle),
                    url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                    type = if (anime.type.equals("TV", ignoreCase = true)) TvType.Anime
                    else if (anime.type.equals("Movie", ignoreCase = true) || anime.episodesCount == 1) TvType.AnimeMovie
                    else TvType.OVA
                ) {
                    if (shouldShowDubSub()) {
                        addDubStatus(getMiniCardDubStatus(entry.hasDub))
                    }
                    addPoster(poster)
                }
            }

        val subEpisodeMap = buildEpisodeSourceMap(subPageData?.anime, subPageData?.episodes.orEmpty())
        val dubEpisodeMap = buildEpisodeSourceMap(dubPageData?.anime, dubPageData?.episodes.orEmpty())
        val subEpisodes = if (shouldMergeVariants) {
            buildMergedEpisodes(
                primaryEpisodes = subEpisodeMap,
                fallbackEpisodes = dubEpisodeMap,
                subEpisodes = subEpisodeMap,
                dubEpisodes = dubEpisodeMap,
            )
        } else {
            buildMergedEpisodes(
                primaryEpisodes = subEpisodeMap,
                fallbackEpisodes = linkedMapOf(),
                subEpisodes = subEpisodeMap,
                dubEpisodes = linkedMapOf(),
            )
        }
        val dubEpisodes = if (shouldMergeVariants) {
            buildMergedEpisodes(
                primaryEpisodes = dubEpisodeMap,
                fallbackEpisodes = subEpisodeMap,
                subEpisodes = subEpisodeMap,
                dubEpisodes = dubEpisodeMap,
                fallbackNamePrefix = "[SUB] - ",
            )
        } else {
            buildMergedEpisodes(
                primaryEpisodes = dubEpisodeMap,
                fallbackEpisodes = linkedMapOf(),
                subEpisodes = linkedMapOf(),
                dubEpisodes = dubEpisodeMap,
            )
        }
        val hasSub = subEpisodeMap.isNotEmpty()
        val hasDub = dubEpisodeMap.isNotEmpty()
        val trailerUrl = getTrailerUrl(primaryAnime)

        return newAnimeLoadResponse(
            name = title.replace(" (ITA)", ""),
            url = getAnimeUrl(primaryAnime),
            type = if (primaryAnime.type == "TV") TvType.Anime
            else if (primaryAnime.type == "Movie" || primaryAnime.episodesCount == 1) TvType.AnimeMovie
            else TvType.OVA,
        ) {
            this.posterUrl = getImage(primaryAnime.imageUrl, primaryAnime.anilistId)
            primaryAnime.cover?.let { this.backgroundPosterUrl = getBanner(it) }
            this.year = primaryAnime.date.toIntOrNull()
            addDuration(primaryAnime.episodesLength.toString() + " minuti")
            when {
                hasSub && hasDub -> {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                hasDub -> {
                    addEpisodes(DubStatus.Dubbed, dubEpisodes)
                }
                hasSub -> {
                    addEpisodes(DubStatus.Subbed, subEpisodes)
                }
            }
            addAniListId(primaryAnime.anilistId)
            addMalId(primaryAnime.malId)
            if (trailerUrl != null) {
                addTrailer(trailerUrl)
            }
            this.showStatus = getShowStatus(primaryAnime.status)
            val audioTag = when {
                hasSub && hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano / \uD83C\uDDEF\uD83C\uDDF5  Giapponese"
                hasDub -> "\uD83C\uDDEE\uD83C\uDDF9  Italiano"
                else -> "\uD83C\uDDEF\uD83C\uDDF5  Giapponese"
            }
            this.tags = listOfNotNull(audioTag, getStatusTag(primaryAnime.status)) + primaryAnime.genres.orEmpty().map { genre ->
                genre.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            this.comingSoon = primaryAnime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }
    }

    // ==================== ANILIST & JIKAN ====================

    private suspend fun getAnilistPoster(anilistId: Int): String? {
        val query = """
        query (${'$'}id: Int) {
            Media(id: ${'$'}id, type: ANIME) {
                coverImage {
                    large
                    medium
                }
            }
        }
        """.trimIndent()

        val body = mapOf("query" to query, "variables" to """{"id":$anilistId}""")
        return runCatching {
            val response = app.post("https://graphql.anilist.co", data = body)
            val anilistObj = parseJson<AnilistResponse>(response.text)
            anilistObj.data.media.coverImage?.let { coverImage ->
                coverImage.large ?: coverImage.medium
            }
        }.getOrNull()
    }

    private suspend fun getTrailerUrl(anime: Anime): String? {
        getAniListTrailer(anime)?.let { return it }
        return getJikanTrailer(anime)
    }

    private suspend fun getAniListTrailer(anime: Anime): String? {
        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title

        val (query, variables) = when {
            anime.anilistId != null -> {
                """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"id":${anime.anilistId}}"""
            }

            anime.malId != null -> {
                """
                query (${'$'}idMal: Int) {
                    Media(idMal: ${'$'}idMal, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"idMal":${anime.malId}}"""
            }

            !searchTitle.isNullOrBlank() -> {
                """
                query (${'$'}search: String) {
                    Media(search: ${'$'}search, type: ANIME) {
                        trailer {
                            id
                            site
                        }
                    }
                }
                """.trimIndent() to """{"search":${org.json.JSONObject.quote(searchTitle)}}"""
            }

            else -> return null
        }

        val body = mapOf("query" to query, "variables" to variables)
        val response = runCatching { app.post("https://graphql.anilist.co", data = body).text }.getOrNull()
            ?: return null
        val media = runCatching { parseJson<AnilistResponse>(response).data.media }.getOrNull()
            ?: return null

        return normalizeAniListTrailerUrl(media.trailer)
    }

    private fun normalizeAniListTrailerUrl(trailer: AnilistTrailer?): String? {
        if (trailer?.site?.equals("youtube", ignoreCase = true) == true && !trailer.id.isNullOrBlank()) {
            return "https://www.youtube.com/watch?v=${trailer.id}"
        }
        return null
    }

    private fun normalizeTrailerUrl(trailer: JikanTrailer?): String? {
        val directUrl = trailer?.url?.takeIf(String::isNotBlank)
        if (directUrl != null) return directUrl

        val youtubeId = trailer?.youtubeId?.takeIf(String::isNotBlank)
            ?: trailer?.embedUrl
                ?.substringAfter("/embed/", "")
                ?.substringBefore("?")
                ?.substringBefore("/")
                ?.takeIf(String::isNotBlank)

        if (youtubeId != null) {
            return "https://www.youtube.com/watch?v=$youtubeId"
        }

        return trailer?.embedUrl?.takeIf(String::isNotBlank)
    }

    private fun normalizeJikanTitle(title: String): String {
        return Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
    }

    private suspend fun getJikanTrailer(anime: Anime): String? {
        anime.malId?.let { malId ->
            val url = "https://api.jikan.moe/v4/anime/$malId/full"
            val response = runCatching { app.get(url).text }.getOrNull() ?: return null
            val trailer = runCatching { parseJson<JikanFullResponse>(response).data.trailer }.getOrNull()
            return normalizeTrailerUrl(trailer)
        }

        val searchTitle = anime.titleEng ?: anime.titleIt ?: anime.title ?: return null
        val searchUrl = "https://api.jikan.moe/v4/anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchTitle)
            .addQueryParameter("limit", "5")
            .build()
            .toString()

        val response = runCatching { app.get(searchUrl).text }.getOrNull() ?: return null
        val candidates = runCatching { parseJson<JikanSearchResponse>(response).data }.getOrNull().orEmpty()
        if (candidates.isEmpty()) return null

        val searchTitles = listOfNotNull(anime.titleIt, anime.titleEng, anime.title)
            .map { normalizeJikanTitle(it) }
            .filter { it.isNotBlank() }

        return candidates.firstNotNullOfOrNull { candidate ->
            val trailerUrl = normalizeTrailerUrl(candidate.trailer) ?: return@firstNotNullOfOrNull null
            val candidateTitles = buildList {
                add(candidate.title)
                candidate.titleEnglish?.let(::add)
                candidate.titleJapanese?.let(::add)
                addAll(candidate.titleSynonyms.orEmpty())
            }.map(::normalizeJikanTitle)

            trailerUrl.takeIf {
                searchTitles.isEmpty() || searchTitles.any(candidateTitles::contains)
            }
        } ?: candidates.firstNotNullOfOrNull { normalizeTrailerUrl(it.trailer) }
    }

    // ==================== ESTRAZIONE LINK VIDEO ====================

    private suspend fun extractEmbedOrStreamUrl(episodePageUrl: String): String? {
        val resp = runCatching { app.get(episodePageUrl, headers = getPageHeaders()) }.getOrNull() ?: return null
        val doc = resp.document

        // 1. Vecchio <video-player embed_url="...">
        val legacyEmbed = doc.select("video-player").attr("embed_url")
        if (legacyEmbed.isNotBlank()) return legacyEmbed

        // 2. Inertia data-page JSON
        val props = extractInertiaProps(doc)
        if (props != null) {
            val propsText = props.toString()
            val vixMatch = Regex("""https?:\\?/\?/(?:www\.)?vixcloud\.[a-z]+/embed/\d+[^"'\s\\]*""")
                .find(propsText)?.value?.replace("\\/", "/")
            if (!vixMatch.isNullOrBlank()) return vixMatch

            val directEmbed = props.asTextOrNull("embed_url", "embedUrl", "stream_url", "link")
                ?: props.get("episode")?.asTextOrNull("embed_url", "embedUrl", "link")
                ?: props.get("video")?.asTextOrNull("embed_url", "embedUrl", "link")
            if (!directEmbed.isNullOrBlank()) return directEmbed
        }

        // 3. Iframe nella pagina
        val iframeSrc = doc.selectFirst("iframe[src*=vixcloud], iframe[src*=embed]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) return iframeSrc

        // 4. Endpoint download /api/episodes/{episode}/downloads
        val epId = episodePageUrl.substringAfterLast("/").toIntOrNull()
        if (epId != null) {
            val dlResp = runCatching {
                app.get("$mainUrl/api/episodes/$epId/downloads", headers = getApiHeaders()).text
            }.getOrNull().orEmpty()
            val dlMatch = Regex("""https?:\\?/\?/[^"'\s\\]+""")
                .find(dlResp)?.value?.replace("\\/", "/")
            if (!dlMatch.isNullOrBlank()) return dlMatch
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        ensureHeadersAndCookies()
        val playbackData = runCatching { parseJson<EpisodePlaybackData>(data) }.getOrNull()
        val playerSources = playbackData?.let(::buildPlayerSourceOptions)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(PlayerSourceOption(label = "[SOURCE]", url = data))

        val shouldLabelSources = playerSources.size > 1

        playerSources.forEach { playerSource ->
            val targetUrl = if (playerSource.url.startsWith("http")) {
                val path = playerSource.url.toHttpUrl().encodedPath
                "$mainUrl$path"
            } else {
                "$mainUrl/${playerSource.url.trimStart('/')}"
            }

            val sourceUrl = extractEmbedOrStreamUrl(targetUrl) ?: playbackData?.embedUrl
            Log.e(LOG_TAG, "loadLinks target=$targetUrl -> sourceUrl=$sourceUrl")
            if (sourceUrl.isNullOrBlank()) return@forEach

            val sourceSuffix = if (shouldLabelSources) " ${playerSource.label}" else ""
            if (sourceUrl.contains("vixcloud", ignoreCase = true) || sourceUrl.contains("/embed/", ignoreCase = true)) {
                VixCloudExtractor(
                    sourceName = "VixCloud$sourceSuffix",
                    displayName = "AnimeUnity$sourceSuffix",
                ).getUrl(
                    url = sourceUrl,
                    referer = "$mainUrl/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            } else {
                loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }
}
