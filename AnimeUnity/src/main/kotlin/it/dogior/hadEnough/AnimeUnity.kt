package it.dogior.hadEnough

import android.content.SharedPreferences
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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        return title.replace(" (ITA)", "")
    }

    private fun getAnimeTitle(anime: Anime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title!!
    }

    private fun getAnimeTitle(anime: LatestEpisodeAnime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title!!
    }

    private fun isDubAnime(anime: Anime): Boolean {
        return anime.dub == 1 || getAnimeTitle(anime).contains("(ITA)")
    }

    private fun isDubAnime(anime: LatestEpisodeAnime): Boolean {
        return anime.dub == 1 || getAnimeTitle(anime).contains("(ITA)")
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
                    dubStatus = getMiniCardDubStatus(entry.hasDub),
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

            collectedTitles += batchTitles
            batchTitles.forEach { uniqueContentKeys += it.cardKey() }
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
            batch.forEach { anime ->
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
                    dubStatus = getMiniCardDubStatus(entry.hasDub),
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

    private suspend fun fetchAnimePageData(url: String): AnimePageData {
        val animePage = app.get(url).document
        return parseAnimePageData(animePage)
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

    private suspend fun findAnimeVariants(currentAnime: Anime): List<Anime> {
        val searchTitle = withoutDubSuffix(getAnimeTitle(currentAnime))
        val responseObject = fetchArchiveBatch(
            "$mainUrl/archivio/get-animes",
            RequestData(title = searchTitle, dubbed = 0)
        )

        return (responseObject.titles.orEmpty() + currentAnime)
            .filter { it.contentKey() == currentAnime.contentKey() }
            .distinctBy(Anime::id)
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
