package it.dogior.hadEnough

import android.content.SharedPreferences
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

typealias Str = BooleanOrString.AsString

@Suppress("unused")
class AnimeUnity(
    private val sharedPref: SharedPreferences? = null,
) : MainAPI() {
    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        get() = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        set(value) {
            field = value
        }
    override var name = Companion.name
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
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

        var name = "AnimeUnity"
        var headers = mapOf(
            "Host" to mainUrl.toHttpUrl().host,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        ).toMutableMap()
    }

    private data class ArchivePageResult(
        val titles: List<Anime>,
        val hasNextPage: Boolean,
    )

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
    )

    private data class EpisodePlaybackData(
        val preferredUrl: String,
        val subUrl: String?,
        val dubUrl: String?,
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
                    "advanced" -> encodeMainPageSectionData("advanced", "$mainUrl/archivio/") to advancedSearchSectionName
                    "latest" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES)) {
                        encodeMainPageSectionData("latest", "$mainUrl/") to getSectionDisplayTitle("latest")
                    } else null
                    "calendar" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_CALENDAR)) {
                        encodeMainPageSectionData("calendar", "$mainUrl/calendario") to getSectionDisplayTitle("calendar")
                    } else null
                    "ongoing" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_ONGOING)) {
                        encodeMainPageSectionData("ongoing", "$mainUrl/archivio/") to getSectionDisplayTitle("ongoing")
                    } else null
                    "popular" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_POPULAR)) {
                        encodeMainPageSectionData("popular", "$mainUrl/archivio/") to getSectionDisplayTitle("popular")
                    } else null
                    "best" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_BEST)) {
                        encodeMainPageSectionData("best", "$mainUrl/archivio/") to getSectionDisplayTitle("best")
                    } else null
                    "upcoming" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_UPCOMING)) {
                        encodeMainPageSectionData("upcoming", "$mainUrl/archivio/") to getSectionDisplayTitle("upcoming")
                    } else null
                    "random" -> if (isSectionEnabled(AnimeUnityPlugin.PREF_SHOW_RANDOM)) {
                        encodeMainPageSectionData("random", "$mainUrl/archivio/") to getSectionDisplayTitle("random")
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
        return title.replace(" (ITA)", "").replace("(ITA)", "").trim()
    }

    private fun getAnimeTitle(anime: Anime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title ?: ""
    }

    private fun getAnimeTitle(anime: LatestEpisodeAnime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title ?: ""
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
            anilistId != null -> "anilist:$anilistId"
            malId != null -> "mal:$malId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun LatestEpisodeAnime.contentKey(): String {
        return when {
            anilistId != null -> "anilist:$anilistId"
            else -> "title:${normalizeJikanTitle(withoutDubSuffix(getAnimeTitle(this)))}"
        }
    }

    private fun Anime.cardKey(): String {
        return if (shouldUseUnifiedDubSubCards()) contentKey() else "anime:$id"
    }

    private fun groupAnimeCards(animes: List<Anime>): List<GroupedAnimeCard> {
        val subAnimes = animes.filterNot(::isDubAnime)
        if (subAnimes.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return subAnimes.map { anime ->
                GroupedAnimeCard(
                    anime = anime,
                    hasDub = false,
                )
            }
        }

        return subAnimes
            .groupBy { it.contentKey() }
            .values
            .map { variants ->
                GroupedAnimeCard(
                    anime = variants.maxByOrNull { it.episodesCount } ?: variants.first(),
                    hasDub = false,
                )
            }
    }

    private fun groupLatestEpisodeCards(items: List<LatestEpisodeItem>): List<GroupedLatestEpisodeCard> {
        val subItems = items.filterNot { isDubAnime(it.anime) }
        if (subItems.isEmpty()) return emptyList()
        if (!shouldUseUnifiedDubSubCards()) {
            return subItems.map { item ->
                GroupedLatestEpisodeCard(
                    anime = item.anime,
                    hasDub = false,
                    episodeNumber = item.number,
                )
            }
        }

        return subItems
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
                    anime = variants.map { it.anime }.maxByOrNull { it.episodesCount } ?: latestEpisode.anime,
                    hasDub = false,
                    episodeNumber = latestEpisode.number,
                )
            }
    }

    private fun getShowStatus(status: String?): ShowStatus? {
        return when (status?.trim()?.lowercase(Locale.ROOT)) {
            "terminato" -> ShowStatus.Completed
            "in corso" -> ShowStatus.Ongoing
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

    private fun applyCardDisplayState(
        response: AnimeSearchResponse,
        poster: String?,
        episodeNumber: Int? = null,
    ) {
        if (shouldShowDubSub()) {
            if (shouldShowEpisodeNumber()) {
                response.addDubStatus(DubStatus.Subbed, episodeNumber)
            } else {
                response.addDubStatus(DubStatus.Subbed)
            }
        }

        response.addPoster(poster)
    }

    private suspend fun ensureHeadersAndCookies(forceReset: Boolean = false) {
        val currentHost = mainUrl.toHttpUrl().host
        val shouldRefreshHeaders = forceReset ||
            headers["Host"] != currentHost ||
            headers["Referer"] != mainUrl ||
            !headers.containsKey("Cookie")

        if (shouldRefreshHeaders) {
            resetHeadersAndCookies()
            setupHeadersAndCookies()
        }
    }

    private suspend fun setupHeadersAndCookies() {
        val response = app.get("$mainUrl/archivio", headers = headers)

        val csrfToken = response.document.head().select("meta[name=csrf-token]").attr("content")
        val cookies =
            "XSRF-TOKEN=${response.cookies["XSRF-TOKEN"]}; animeunity_session=${response.cookies["animeunity_session"]}"
        val h = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json;charset=utf-8",
            "X-CSRF-Token" to csrfToken,
            "Referer" to mainUrl,
            "Cookie" to cookies
        )
        headers.putAll(h)
    }

    private fun resetHeadersAndCookies() {
        if (headers.isNotEmpty()) {
            headers.clear()
        }
        headers["Host"] = mainUrl.toHttpUrl().host
        headers["User-Agent"] =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
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
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    poster = poster,
                    episodeNumber = episodeNumber
                )
            }
        }
    }

    private suspend fun fetchArchiveBatch(url: String, requestData: RequestData): ApiResponse {
        val response = app.post(url, headers = headers, requestBody = requestData.toRequestBody())
        return parseJson<ApiResponse>(response.text)
    }

    private suspend fun fetchArchiveSectionPage(
        url: String,
        requestData: RequestData,
        page: Int,
        sectionCount: Int,
    ): ArchivePageResult {
        val collectedTitles = mutableListOf<Anime>()
        val uniqueContentKeys = linkedSetOf<String>()
        var nextOffset = (page - 1) * sectionCount
        var total = 0

        while (uniqueContentKeys.size < sectionCount) {
            val responseObject = fetchArchiveBatch(url, requestData.copy(offset = nextOffset))
            total = responseObject.total

            val batchTitles = responseObject.titles.orEmpty()
            if (batchTitles.isEmpty()) break

            val subBatch = batchTitles.filterNot(::isDubAnime)
            collectedTitles += subBatch
            subBatch.forEach { uniqueContentKeys += it.cardKey() }
            nextOffset += batchTitles.size

            if (nextOffset >= total || batchTitles.size < ARCHIVE_BATCH_SIZE) {
                break
            }
        }

        return ArchivePageResult(
            titles = collectedTitles,
            hasNextPage = nextOffset < total,
        )
    }

    private suspend fun fetchRandomTitles(url: String, sectionCount: Int): Pair<List<Anime>, Int> {
        val requestData = RequestData(dubbed = 0)
        val initialResponse = fetchArchiveBatch(url, requestData.copy(offset = 0))
        val total = initialResponse.total
        val collectedTitles = linkedMapOf<Int, Anime>()
        val requestedOffsets = mutableSetOf<Int>()
        val maxAttempts = ((sectionCount + ARCHIVE_BATCH_SIZE - 1) / ARCHIVE_BATCH_SIZE) * 3

        fun collectBatch(batch: List<Anime>) {
            batch.filterNot(::isDubAnime).forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
        }

        if (total <= ARCHIVE_BATCH_SIZE) {
            collectBatch(initialResponse.titles.orEmpty())
            return collectedTitles.values.shuffled().take(sectionCount) to total
        }

        repeat(maxAttempts) {
            if (collectedTitles.size >= sectionCount) {
                return@repeat
            }

            val maxOffset = (total - ARCHIVE_BATCH_SIZE).coerceAtLeast(0)
            val randomOffset = if (maxOffset == 0) 0 else (0..maxOffset).random()
            if (!requestedOffsets.add(randomOffset)) {
                return@repeat
            }

            collectBatch(fetchArchiveBatch(url, requestData.copy(offset = randomOffset)).titles.orEmpty())
        }

        if (collectedTitles.isEmpty()) {
            collectBatch(initialResponse.titles.orEmpty())
        }

        return collectedTitles.values.shuffled().take(sectionCount) to total
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
                    anime.type == "TV" -> TvType.Anime
                    anime.type == "Movie" || anime.episodesCount == 1 -> TvType.AnimeMovie
                    else -> TvType.OVA
                }
            ).apply {
                applyCardDisplayState(
                    response = this,
                    poster = poster,
                    episodeNumber = entry.episodeNumber.toIntOrNull()
                )
            }
        }
    }

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
        if (!imageUrl.isNullOrEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageCdnHost()}/anime/$fileName"
            } catch (_: Exception) {}
        }
        return anilistId?.let { getAnilistPoster(it) }
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

        addSource(playbackData.subUrl ?: playbackData.preferredUrl, "[SUB]")
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
                    dubUrl = null,
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

    private suspend fun parseAnimePageData(animePage: org.jsoup.nodes.Document): AnimePageData {
        val relatedAnimeJsonArray = animePage.select("layout-items").attr("items-json")
        val relatedAnime = relatedAnimeJsonArray
            .takeIf(String::isNotBlank)
            ?.let { runCatching { parseJson<List<Anime>>(it) }.getOrDefault(emptyList()) }
            ?: emptyList()

        val videoPlayer = animePage.select("video-player")
        val anime = parseJson<Anime>(videoPlayer.attr("anime"))
        val initialEpisodes = parseJson<List<Episode>>(videoPlayer.attr("episodes"))
        val totalEpisodes = videoPlayer.attr("episodes_count").toIntOrNull() ?: initialEpisodes.size

        return AnimePageData(
            anime = anime,
            relatedAnime = relatedAnime,
            episodes = getAllEpisodes(anime, initialEpisodes, totalEpisodes),
        )
    }

    private suspend fun getAllEpisodes(
        anime: Anime,
        initialEpisodes: List<Episode>,
        totalEpisodes: Int,
    ): List<Episode> {
        val episodes = initialEpisodes.toMutableList()
        val isEpisodeNumberMultipleOfRange = totalEpisodes % 120 == 0
        val range = if (isEpisodeNumberMultipleOfRange) totalEpisodes / 120 else (totalEpisodes / 120) + 1

        if (totalEpisodes > 120) {
            for (i in 2..range) {
                val endRange = if (i == range) totalEpisodes else i * 120
                val infoUrl = "$mainUrl/info_api/${anime.id}/1?start_range=${1 + (i - 1) * 120}&end_range=${endRange}"
                val info = app.get(infoUrl).text
                val animeInfo = parseJson<AnimeInfo>(info)
                episodes.addAll(animeInfo.episodes)
            }
        }

        return episodes
    }

    private suspend fun getAnilistPoster(anilistId: Int): String {
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
        val response = app.post("https://graphql.anilist.co", data = body)
        val anilistObj = parseJson<AnilistResponse>(response.text)

        return anilistObj.data.media.coverImage?.let { coverImage ->
            coverImage.large ?: coverImage.medium!!
        } ?: throw IllegalStateException("No valid image found")
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
                """.trimIndent() to """{"search":${JSONObject.quote(searchTitle)}}"""
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (usesModernSite()) return modernMainPage(page, request)
        val sectionData = decodeMainPageSectionData(request.data)

        if (sectionData.key == "latest") {
            return getLatestEpisodesMainPage(page, request.name)
        }
        if (sectionData.key == "calendar") {
            return getCalendarMainPage(page, request.name, sectionData.baseUrl)
        }
        if (sectionData.key == "random") {
            return getRandomMainPage(page, request.name, sectionData.baseUrl)
        }

        val url = sectionData.baseUrl + "get-animes"
        ensureHeadersAndCookies()

        val requestData = getDataPerHomeSection(sectionData.key)
        val sectionCount = getSectionCount(sectionData.key)
        val archivePage = fetchArchiveSectionPage(url, requestData, page, sectionCount)
        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = if (archivePage.titles.isNotEmpty()) {
                    searchResponseBuilder(archivePage.titles)
                } else {
                    emptyList()
                },
                isHorizontalImages = false
            ),
            archivePage.hasNextPage
        )
    }

    private suspend fun getLatestEpisodesMainPage(page: Int, sectionTitle: String): HomePageResponse {
        val sectionCount = getSectionCount("latest")
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = sectionTitle, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val latestEpisodesJson = app.get("$mainUrl/?page=1").document
            .selectFirst("#ultimi-episodi layout-items")
            ?.attr("items-json")
            .orEmpty()

        val latestEpisodes = latestEpisodesJson
            .takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching { parseJson<LatestEpisodesPage>(json).episodes }.getOrDefault(emptyList())
            }
            ?.filterNot { isDubAnime(it.anime) }
            ?.take(sectionCount)
            ?: emptyList()

        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = latestEpisodesResponseBuilder(latestEpisodes),
                isHorizontalImages = false
            ),
            false
        )
    }

    private suspend fun getCalendarMainPage(
        page: Int,
        sectionTitle: String,
        requestUrl: String,
    ): HomePageResponse {
        val currentDay = getCurrentItalianDayName()
        val calendarTitle = "$sectionTitle ($currentDay)"
        val sectionCount = getSectionCount("calendar")

        if (page > 1) {
            return newHomePageResponse(
                HomePageList(name = calendarTitle, list = emptyList(), isHorizontalImages = false),
                false
            )
        }

        val calendarAnime = app.get(requestUrl).document
            .select("calendario-item")
            .mapNotNull { item ->
                val animeJson = item.attr("a")
                if (animeJson.isBlank()) return@mapNotNull null

                val anime = runCatching { parseJson<Anime>(animeJson) }.getOrNull() ?: return@mapNotNull null
                if (isDubAnime(anime)) return@mapNotNull null
                val episodeNumber = extractCalendarEpisodeNumber(item, anime)

                if (normalizeDayName(anime.day) == normalizeDayName(currentDay)) {
                    anime to episodeNumber
                } else {
                    null
                }
            }
            .distinctBy { it.first.contentKey() }
            .take(sectionCount)

        return newHomePageResponse(
            HomePageList(
                name = calendarTitle,
                list = calendarAnime.amap { (anime, ep) ->
                    searchResponseBuilder(listOf(anime), ep).first()
                },
                isHorizontalImages = false
            ),
            false
        )
    }

    private fun extractCalendarEpisodeNumber(item: Element, anime: Anime): Int? {
        item.attr("episodes_count")
            .trim()
            .toIntOrNull()
            ?.let { releasedEpisodes ->
                return releasedEpisodes + 1
            }

        return anime.episodes
            ?.mapNotNull { it.number.toIntOrNull() }
            ?.maxOrNull()
            ?.plus(1)
    }

    private suspend fun getRandomMainPage(
        page: Int,
        sectionTitle: String,
        requestUrl: String,
    ): HomePageResponse {
        val url = "${requestUrl}get-animes"
        ensureHeadersAndCookies()

        val sectionCount = getSectionCount("random")
        val (titles, total) = fetchRandomTitles(url, sectionCount)

        return newHomePageResponse(
            HomePageList(
                name = sectionTitle,
                list = searchResponseBuilder(titles),
                isHorizontalImages = false
            ),
            page < 5 && total > sectionCount
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

    private fun getDataPerHomeSection(section: String) = when (section) {
        "advanced" -> AnimeUnityPlugin.getAdvancedSearchRequestData(sharedPref)
        "popular" -> RequestData(orderBy = Str("Popolarità"), dubbed = 0)
        "upcoming" -> RequestData(status = Str("In Uscita"), dubbed = 0)
        "best" -> RequestData(orderBy = Str("Valutazione"), dubbed = 0)
        "ongoing" -> RequestData(orderBy = Str("Popolarità"), status = Str("In Corso"), dubbed = 0)
        else -> RequestData(dubbed = 0)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (usesModernSite()) return modernSearch(query)
        val url = "$mainUrl/archivio/get-animes"
        ensureHeadersAndCookies(forceReset = true)

        val requestBody = RequestData(title = query, dubbed = 0).toRequestBody()
        val response = app.post(url, headers = headers, requestBody = requestBody)

        val responseObject = parseJson<ApiResponse>(response.text)
        val titles = responseObject.titles.orEmpty().filterNot(::isDubAnime)

        return searchResponseBuilder(titles)
    }

    override suspend fun load(url: String): LoadResponse {
        if (usesModernSite()) return modernLoad(url)
        ensureHeadersAndCookies(forceReset = true)
        val animePage = app.get(url).document
        val currentPageData = parseAnimePageData(animePage)
        val primaryAnime = currentPageData.anime
        val title = getAnimeTitle(primaryAnime)
        val relatedAnimes = groupAnimeCards(currentPageData.relatedAnime).amap { entry ->
            val anime = entry.anime
            val relatedTitle = getAnimeTitle(anime)
            val poster = getImage(anime.imageUrl, anime.anilistId)
            newAnimeSearchResponse(
                name = withoutDubSuffix(relatedTitle),
                url = "$mainUrl/anime/${anime.id}-${anime.slug}",
                type = if (anime.type == "TV") TvType.Anime
                else if (anime.type == "Movie" || anime.episodesCount == 1) TvType.AnimeMovie
                else TvType.OVA
            ) {
                if (shouldShowDubSub()) {
                    addDubStatus(DubStatus.Subbed)
                }
                addPoster(poster)
            }
        }

        val subEpisodeMap = buildEpisodeSourceMap(primaryAnime, currentPageData.episodes)
        val subEpisodes = buildMergedEpisodes(
            primaryEpisodes = subEpisodeMap,
            fallbackEpisodes = linkedMapOf(),
            subEpisodes = subEpisodeMap,
            dubEpisodes = linkedMapOf(),
        )
        val trailerUrl = getTrailerUrl(primaryAnime)

        return newAnimeLoadResponse(
            name = withoutDubSuffix(title),
            url = getAnimeUrl(primaryAnime),
            type = if (primaryAnime.type == "TV") TvType.Anime
            else if (primaryAnime.type == "Movie" || primaryAnime.episodesCount == 1) TvType.AnimeMovie
            else TvType.OVA,
        ) {
            this.posterUrl = getImage(primaryAnime.imageUrl, primaryAnime.anilistId)
            primaryAnime.cover?.let { this.backgroundPosterUrl = getBanner(it) }
            this.year = primaryAnime.date.toIntOrNull()
            addDuration(primaryAnime.episodesLength.toString() + " minuti")
            if (subEpisodes.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, subEpisodes)
            }
            if (trailerUrl != null) {
                addTrailer(trailerUrl)
            }
            this.showStatus = getShowStatus(primaryAnime.status)
            this.plot = primaryAnime.plot
            this.tags = listOfNotNull("\uD83C\uDDEF\uD83C\uDDF5  Giapponese", getStatusTag(primaryAnime.status)) +
                primaryAnime.genres.map { genre ->
                    genre.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
            this.comingSoon = primaryAnime.status == "In uscita prossimamente"
            this.recommendations = relatedAnimes
        }
    }

    private fun getBanner(imageUrl: String): String {
        if (imageUrl.isNotEmpty()) {
            try {
                val fileName = imageUrl.substringAfterLast("/")
                return "https://${getImageCdnHost()}/anime/$fileName"
            } catch (_: Exception) {}
        }
        return imageUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playbackData = runCatching { parseJson<EpisodePlaybackData>(data) }.getOrNull()
        val playerSources = playbackData?.let(::buildPlayerSourceOptions)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(PlayerSourceOption(label = "[SUB]", url = data))

        val shouldLabelSources = playerSources.size > 1

        playerSources.forEach { playerSource ->
            check(!usesModernSite() || playerSource.url.toHttpUrl().host.removePrefix("www.") != "animeunity.co") {
                "AnimeUnity: episodio salvato sul vecchio sito; riaprire il titolo e selezionare nuovamente l'episodio"
            }
            val document = app.get(playerSource.url).document
            val sourceUrl = if (usesModernSite()) modernProps(document).optString("embedUrl")
                else document.select("video-player").attr("embed_url")
            if (sourceUrl.isBlank()) return@forEach

            val sourceSuffix = if (shouldLabelSources) " ${playerSource.label}" else ""
            VixCloudExtractor(
                sourceName = "VixCloud$sourceSuffix",
                displayName = "AnimeUnity$sourceSuffix",
            ).getUrl(
                url = sourceUrl,
                referer = mainUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        return true
    }

    private fun usesModernSite() = mainUrl.toHttpUrl().host.removePrefix("www.") == "animeunity.cx"

    private fun modernProps(document: org.jsoup.nodes.Document): JSONObject {
        val data = document.selectFirst("[data-page]")?.attr("data-page")
            ?: error("AnimeUnity: dati della pagina mancanti; verificare il dominio o la risposta del sito")
        return JSONObject(data).getJSONObject("props")
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun modernAnimeUrl(anime: JSONObject) = "$mainUrl/anime/${anime.getInt("id")}-${anime.getString("slug")}"
    private fun modernType(anime: JSONObject) = when (anime.optString("type")) {
        "movie" -> TvType.AnimeMovie
        "ova", "ona", "special" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun isModernDubOnly(anime: JSONObject): Boolean {
        val title = (anime.text("title_it") ?: anime.optString("title")).lowercase(Locale.ROOT)
        val slug = anime.optString("slug").lowercase(Locale.ROOT)
        if (title.contains("(ita)") || slug.endsWith("-ita")) return true

        val variants = anime.optJSONArray("variants")?.objects().orEmpty()
        return variants.isNotEmpty() && variants.all { it.optString("language").equals("ita", ignoreCase = true) }
    }

    private fun modernCards(animes: List<JSONObject>): List<SearchResponse> {
        return animes
            .filterNot(::isModernDubOnly)
            .groupBy { it.getInt("id") }
            .values
            .map { entries ->
                val anime = entries.first()
                val subVariants = anime.optJSONArray("variants")?.objects().orEmpty()
                    .filterNot { it.optString("language").equals("ita", ignoreCase = true) }
                val latest = entries
                    .filterNot { it.optString("_latest_language").equals("ita", ignoreCase = true) }
                    .mapNotNull { it.text("_latest_number")?.toDoubleOrNull() }
                    .maxOrNull()
                    ?.toInt()
                val title = anime.text("title_it") ?: anime.getString("title")
                val cardUrl = modernAnimeUrl(anime)
                newAnimeSearchResponse(buildDisplayTitle(title, latest), cardUrl, modernType(anime)).apply {
                    addPoster(anime.optJSONObject("images")?.text("poster"))
                    if (shouldShowDubSub()) {
                        val subEpisodesCount = subVariants.maxOfOrNull { it.optInt("episodes_count") }?.takeIf { it > 0 }
                        addDubStatus(
                            DubStatus.Subbed,
                            if (shouldShowEpisodeNumber()) latest ?: subEpisodesCount else null
                        )
                    }
                }
            }
    }

    private suspend fun modernGet(path: String): JSONObject {
        repeat(3) { attempt ->
            val response = app.get("$mainUrl/$path")
            if (response.code == 429 && attempt < 2) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: 60L
                check(retryAfter in 1..120) { "AnimeUnity: troppe richieste; riprovare più tardi" }
                kotlinx.coroutines.delay(retryAfter * 1000L)
            } else {
                check(response.code == 200) { "AnimeUnity: HTTP ${response.code} per /${path.substringBefore('?')}" }
                return JSONObject(response.text)
            }
        }
        error("AnimeUnity: troppe richieste; riprovare più tardi")
    }

    private suspend fun modernPage(
        path: String,
        page: Int,
        count: Int,
        params: Map<String, String> = emptyMap(),
    ): Pair<List<JSONObject>, Boolean> {
        val offset = (page - 1) * count
        val pageSize = if (path == "api/home/latest-episodes") 20 else 30
        var sourcePage = offset / pageSize + 1
        var skip = offset % pageSize
        val items = mutableListOf<JSONObject>()
        var more: Boolean
        do {
            val query = (params + ("page" to sourcePage.toString())).entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
            }
            val result = modernGet("$path?$query")
            val rawBatch = result.getJSONArray("data").objects().drop(skip)
            val filteredBatch = if (path == "api/home/latest-episodes") {
                rawBatch.filter { item ->
                    !item.optString("language").equals("ita", ignoreCase = true) &&
                        item.optJSONObject("episode")?.optJSONObject("anime")?.let(::isModernDubOnly) != true
                }
            } else {
                rawBatch.filterNot(::isModernDubOnly)
            }
            val remaining = count - items.size
            items.addAll(filteredBatch.take(remaining))
            more = filteredBatch.size > remaining || result.text("next_page_url") != null
            if (items.size >= count || !more || rawBatch.isEmpty()) break
            skip = 0
            sourcePage++
        } while (true)
        return items to more
    }

    private suspend fun modernSearch(query: String): List<SearchResponse> {
        val data = modernGet("api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"))
            .getJSONArray("data")
            .objects()
            .filterNot(::isModernDubOnly)
        return modernCards(data)
    }

    private suspend fun modernMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val key = decodeMainPageSectionData(request.data).key
        val count = getSectionCount(key)
        var hasNext = false
        val animes = when (key) {
            "latest" -> {
                val (items, more) = modernPage("api/home/latest-episodes", page, count)
                hasNext = more
                items.map {
                    val episode = it.getJSONObject("episode")
                    JSONObject(episode.getJSONObject("anime").toString())
                        .put("_latest_number", episode.getString("number"))
                        .put("_latest_language", it.getString("language"))
                }
            }
            "calendar" -> {
                if (page > 1) emptyList() else {
                    val result = modernGet("api/calendar")
                    val today = result.keys().asSequence().firstOrNull { it.contains("Oggi") }
                    today?.let {
                        result.getJSONArray(it).objects()
                            .filterNot { entry -> entry.optBoolean("isDub") }
                            .mapNotNull { entry ->
                                entry.optJSONObject("anime")?.takeUnless(::isModernDubOnly)
                            }
                    }.orEmpty()
                }
            }
            else -> {
                val params = linkedMapOf("page" to page.toString(), "per_page" to count.toString())
                when (key) {
                    "ongoing" -> params["status"] = "ongoing"
                    "upcoming" -> params["status"] = "upcoming"
                    "popular" -> params["sort_by"] = "members"
                    "best" -> params["sort_by"] = "score"
                    "random" -> {
                        val total = modernGet("api/archive").optInt("total", 1).coerceAtLeast(1)
                        val sectionPages = ((total + count - 1) / count).coerceAtLeast(1)
                        params["page"] = (1..sectionPages).random().toString()
                    }
                    "advanced" -> {
                        val config = AnimeUnityPlugin.getAdvancedSearchConfig(sharedPref)
                        if (config.title.isNotBlank()) params["query"] = config.title
                        config.year?.let { params["year_from"] = it; params["year_to"] = it }
                        config.status?.let { params["status"] = mapOf("In Corso" to "ongoing", "Terminato" to "completed", "In Uscita" to "upcoming", "Droppato" to "dropped")[it] ?: it }
                        config.type?.let { params["type"] = it.lowercase().replace(" ", "_") }
                        config.season?.let { params["season"] = mapOf("Inverno" to "winter", "Primavera" to "spring", "Estate" to "summer", "Autunno" to "fall")[it] ?: it }
                        config.genre?.let { genre ->
                            val props = modernProps(app.get("$mainUrl/archive").document)
                            props.optJSONArray("animeGenres")?.objects()?.firstOrNull { it.optString("name").equals(genre.name, true) }
                                ?.let { params["genres[]"] = it.getInt("id").toString() }
                        }
                        config.order?.let { params["sort_by"] = when (it) { "Valutazione" -> "score"; "Popolarità" -> "members"; "Lista Z-A" -> "title_za"; else -> "title_az" } }
                    }
                }
                val (items, more) = modernPage("api/archive", if (key == "random") params.getValue("page").toInt() else page, count, params - "page" - "per_page")
                hasNext = key != "random" && more
                items
            }
        }
        return newHomePageResponse(HomePageList(request.name, modernCards(animes.take(count)), false), hasNext)
    }

    private suspend fun modernLoad(url: String): LoadResponse {
        val inputUrl = url.toHttpUrl()
        val legacyHost = inputUrl.host.removePrefix("www.") == "animeunity.co"
        val path = if (legacyHost) {
            val slug = inputUrl.pathSegments.getOrNull(1)?.substringAfter('-')
                ?: error("AnimeUnity: collegamento salvato non valido")
            val matches = modernGet("api/search?q=" + java.net.URLEncoder.encode(slug.replace('-', ' '), "UTF-8"))
                .getJSONArray("data").objects()
            val match = matches.firstOrNull { it.getString("slug") == slug }
                ?: error("AnimeUnity: titolo salvato non trovato sul nuovo sito; cercarlo nuovamente")
            modernAnimeUrl(match).toHttpUrl().encodedPath
        } else inputUrl.encodedPath

        val props = modernProps(app.get("$mainUrl$path").document)
        val anime = props.getJSONObject("anime")
        val animeUrl = modernAnimeUrl(anime)
        val episodes = mutableListOf<JSONObject>()
        var start = 1
        do {
            val result = modernGet("api/anime/${anime.getInt("id")}/episodes?start=$start")
            val batch = result.getJSONArray("episodes").objects()
            episodes.addAll(batch)
            val tabSize = result.optInt("tab_size", 120)
            check(tabSize > 0) { "AnimeUnity: dimensione pagina episodi non valida" }
            start += tabSize
        } while (start <= result.getInt("total"))

        return newAnimeLoadResponse(
            withoutDubSuffix(anime.text("title_it") ?: anime.getString("title")),
            animeUrl,
            modernType(anime)
        ) {
            posterUrl = anime.optJSONObject("images")?.text("poster")
            backgroundPosterUrl = anime.optJSONObject("images")?.text("banner")
            plot = anime.text("plot")
            year = anime.optInt("year").takeIf { it > 0 }
            anime.optInt("episode_runtime").takeIf { it > 0 }?.let { addDuration(it.toString()) }
            showStatus = when (anime.optString("default_status")) {
                "completed" -> ShowStatus.Completed
                "ongoing" -> ShowStatus.Ongoing
                else -> null
            }
            comingSoon = anime.optString("default_status") == "upcoming"
            tags = anime.optJSONArray("genres")?.objects()?.map { it.getString("name") }

            val sub = linkedMapOf<String, EpisodeSource>()
            episodes.distinctBy { it.getInt("id") }.forEach { episode ->
                val video = episode.getJSONArray("videos").objects().firstOrNull {
                    !it.optString("language").equals("ita", ignoreCase = true) && !it.optBoolean("is_hidden")
                } ?: return@forEach
                val number = episode.getString("number")
                sub[number] = EpisodeSource(number, "$animeUrl/${video.getInt("id")}")
            }

            if (sub.isNotEmpty()) {
                addEpisodes(
                    DubStatus.Subbed,
                    buildMergedEpisodes(sub, linkedMapOf(), sub, linkedMapOf())
                )
            }

            recommendations = modernCards(props.optJSONArray("relatedAnimes")?.objects().orEmpty())
        }
    }
}
