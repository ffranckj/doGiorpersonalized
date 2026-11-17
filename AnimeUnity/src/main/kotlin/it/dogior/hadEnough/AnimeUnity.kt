package it.dogior.hadEnough

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

typealias Str = BooleanOrString.AsString

@Suppress("unused")
class AnimeUnity(
    private val sharedPref: SharedPreferences? = null,
) : MainAPI() {

    @Volatile
    private var resolvedBaseUrl: String? = null

    @Volatile
    private var useCloudflareBypass: Boolean = false

    private val isRefreshingSession = AtomicBoolean(false)
    private val cfKiller by lazy { CloudflareKiller() }

    override var mainUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref)
        get() = resolvedBaseUrl ?: AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref).trimEnd('/')
        set(value) {
            field = value.trimEnd('/')
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

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        var name = "AnimeUnity"
        var headers = mapOf(
            "User-Agent" to USER_AGENT
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

    data class EpisodePlaybackData(
        @JsonProperty("preferredUrl") val preferredUrl: String,
        @JsonProperty("subUrl") val subUrl: String? = null,
        @JsonProperty("dubUrl") val dubUrl: String? = null,
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
        val sections = mutableListOf<String>()
        if (AnimeUnityPlugin.isAdvancedSearchEnabled(sharedPref)) {
            sections.add("advanced")
        }
        sections.addAll(order.split(","))

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
        return anime.titleIt ?: anime.titleEng ?: anime.title.orEmpty()
    }

    private fun getAnimeTitle(anime: LatestEpisodeAnime): String {
        return anime.titleIt ?: anime.titleEng ?: anime.title.orEmpty()
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

    private fun hasValidSession(): Boolean {
        val cookie = headers["Cookie"].orEmpty()
        val csrf = headers["X-CSRF-Token"].orEmpty().ifBlank { headers["X-XSRF-TOKEN"].orEmpty() }
        return cookie.isNotBlank() && !cookie.contains("=null") && csrf.isNotBlank()
    }

    private fun getPageHeaders(): Map<String, String> {
        val base = mainUrl
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = headers["User-Agent"] ?: USER_AGENT
        map["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        map["Accept-Language"] = "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
        map["Referer"] = "$base/"
        val cookie = headers["Cookie"]
        if (!cookie.isNullOrBlank() && !cookie.contains("=null")) {
            map["Cookie"] = cookie
        }
        return map
    }

    private fun getApiPostHeaders(): Map<String, String> {
        val base = mainUrl
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = headers["User-Agent"] ?: USER_AGENT
        map["Accept"] = "application/json, text/plain, */*"
        map["Accept-Language"] = "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
        map["Content-Type"] = "application/json;charset=utf-8"
        map["X-Requested-With"] = "XMLHttpRequest"
        map["Origin"] = base
        map["Referer"] = "$base/archivio"
        headers["X-CSRF-Token"]?.takeIf(String::isNotBlank)?.let { map["X-CSRF-Token"] = it }
        headers["X-XSRF-TOKEN"]?.takeIf(String::isNotBlank)?.let { map["X-XSRF-TOKEN"] = it }
        headers["Cookie"]?.takeIf { it.isNotBlank() && !it.contains("=null") }?.let { map["Cookie"] = it }
        return map
    }

    private suspend fun ensureHeadersAndCookies(forceReset: Boolean = false) {
        var waitTicks = 0
        while (isRefreshingSession.get() && waitTicks < 100) {
            delay(50)
            waitTicks++
        }

        if (!forceReset && hasValidSession()) {
            return
        }

        if (isRefreshingSession.compareAndSet(false, true)) {
            try {
                if (forceReset || !hasValidSession()) {
                    setupHeadersAndCookies()
                }
            } finally {
                isRefreshingSession.set(false)
            }
        } else {
            var secondWait = 0
            while (isRefreshingSession.get() && secondWait < 100) {
                delay(50)
                secondWait++
            }
        }
    }

    private suspend fun setupHeadersAndCookies() {
        val configuredUrl = AnimeUnityPlugin.getConfiguredBaseUrl(sharedPref).trimEnd('/')
        val candidateUrls = listOfNotNull(
            resolvedBaseUrl,
            configuredUrl,
            "https://www.animeunity.so",
            "https://www.animeunity.co",
        ).map { it.trimEnd('/') }.distinct()

        val initialHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        )

        for (candidateBase in candidateUrls) {
            var response = runCatching {
                app.get(
                    "$candidateBase/archivio",
                    headers = initialHeaders,
                    interceptor = if (useCloudflareBypass) cfKiller else null
                )
            }.getOrNull()

            if (response == null || response.code in listOf(403, 421, 503)) {
                useCloudflareBypass = true
                response = runCatching {
                    app.get(
                        "$candidateBase/archivio",
                        headers = initialHeaders,
                        interceptor = cfKiller
                    )
                }.getOrNull()
            }

            if (response == null) continue

            val effectiveBase = runCatching {
                val finalHttpUrl = response.url.toHttpUrl()
                "${finalHttpUrl.scheme}://${finalHttpUrl.host}"
            }.getOrDefault(candidateBase)

            val metaCsrf = response.document.selectFirst("meta[name=csrf-token]")
                ?.attr("content")
                ?.trim()
                .orEmpty()

            val rawXsrf = response.cookies["XSRF-TOKEN"]
                ?.takeIf { it.isNotBlank() && it != "null" }
                .orEmpty()

            val decodedXsrf = if (rawXsrf.isNotBlank()) {
                runCatching { URLDecoder.decode(rawXsrf, "UTF-8") }.getOrDefault(rawXsrf)
            } else {
                ""
            }

            val csrfToken = metaCsrf.ifBlank { decodedXsrf }

            val cookieList = mutableListOf<String>()
            for ((k, v) in response.cookies) {
                if (k.isNotBlank() && v.isNotBlank() && v != "null") {
                    cookieList.add("$k=$v")
                }
            }

            if (useCloudflareBypass) {
                val cfCookie = runCatching { cfKiller.getCookieHeaders(effectiveBase)["Cookie"] }.getOrNull()
                if (!cfCookie.isNullOrBlank()) {
                    cookieList.add(cfCookie)
                }
            }

            val cookieHeader = cookieList.distinct().joinToString("; ")
            if (csrfToken.isNotBlank() && cookieHeader.isNotBlank()) {
                resolvedBaseUrl = effectiveBase
                val newHeaders = LinkedHashMap<String, String>()
                newHeaders["User-Agent"] = USER_AGENT
                newHeaders["X-Requested-With"] = "XMLHttpRequest"
                newHeaders["Content-Type"] = "application/json;charset=utf-8"
                newHeaders["X-CSRF-Token"] = csrfToken
                if (decodedXsrf.isNotBlank()) {
                    newHeaders["X-XSRF-TOKEN"] = decodedXsrf
                }
                newHeaders["Origin"] = effectiveBase
                newHeaders["Referer"] = "$effectiveBase/archivio"
                newHeaders["Cookie"] = cookieHeader
                headers = newHeaders
                return
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

    private suspend fun fetchArchiveBatch(
        @Suppress("UNUSED_PARAMETER") url: String,
        requestData: RequestData,
    ): ApiResponse? {
        ensureHeadersAndCookies()
        val endpoint = "$mainUrl/archivio/get-animes"

        var response = runCatching {
            app.post(
                endpoint,
                headers = getApiPostHeaders(),
                requestBody = requestData.toRequestBody(),
                interceptor = if (useCloudflareBypass) cfKiller else null
            )
        }.getOrNull()

        val firstText = response?.text?.trimStart().orEmpty()
        if (response == null || response.code in listOf(403, 419, 421, 503) || !firstText.startsWith("{")) {
            ensureHeadersAndCookies(forceReset = true)
            val retryEndpoint = "$mainUrl/archivio/get-animes"
            response = runCatching {
                app.post(
                    retryEndpoint,
                    headers = getApiPostHeaders(),
                    requestBody = requestData.toRequestBody(),
                    interceptor = if (useCloudflareBypass) cfKiller else null
                )
            }.getOrNull()
        }

        val finalText = response?.text?.trimStart().orEmpty()
        if (!finalText.startsWith("{")) {
            return null
        }

        return runCatching { parseJson<ApiResponse>(finalText) }.getOrNull()
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
            val responseObject = fetchArchiveBatch(url, requestData.copy(offset = nextOffset)) ?: break
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
            ?: return emptyList<Anime>() to 0
        val total = initialResponse.total
        val collectedTitles = linkedMapOf<Int, Anime>()
        val requestedOffsets = mutableSetOf<Int>()
        val maxAttempts = ((sectionCount + ARCHIVE_BATCH_SIZE - 1) / ARCHIVE_BATCH_SIZE) * 3

        if (total <= ARCHIVE_BATCH_SIZE) {
            initialResponse.titles.orEmpty().forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
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

            val batch = fetchArchiveBatch(url, requestData.copy(offset = randomOffset))?.titles.orEmpty()
            batch.forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
        }

        if (collectedTitles.isEmpty()) {
            initialResponse.titles.orEmpty().forEach { anime ->
                collectedTitles.putIfAbsent(anime.id, anime)
            }
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
                if (fileName.isNotBlank()) {
                    return "https://${getImageCdnHost()}/anime/$fileName"
                }
            } catch (_: Exception) {
            }
            if (imageUrl.startsWith("http")) return imageUrl
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

        val candidates = when (playbackData.preferredUrl) {
            playbackData.dubUrl -> listOf(
                "[DUB]" to playbackData.dubUrl,
                "[SUB]" to playbackData.subUrl,
            )
            playbackData.subUrl -> listOf(
                "[SUB]" to playbackData.subUrl,
                "[DUB]" to playbackData.dubUrl,
            )
            else -> listOf(
                "[SOURCE]" to playbackData.preferredUrl,
                "[DUB]" to playbackData.dubUrl,
                "[SUB]" to playbackData.subUrl,
            )
        }

        for ((label, rawUrl) in candidates) {
