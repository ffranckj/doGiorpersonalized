package it.dogior.hadEnough

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeUnity : MainAPI() {
    override var mainUrl = "https://animeunity.cx"
    override var name = "AnimeUnity"
    override var lang = "it"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val TAG = "AU_DEBUG"
        private val mapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private var sessionCookies: Map<String, String> = emptyMap()
        private var xsrfToken: String = ""
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/home/latest-episodes" to "Ultimi Episodi",
        "$mainUrl/api/top-anime?period=day" to "Top Anime Oggi",
        "$mainUrl/api/top-anime?period=week" to "Top Anime Settimana",
        "$mainUrl/api/top-anime?period=month&sort_by=score" to "Top Anime Mese",
        "$mainUrl/api/archive?status=ongoing&sort_by=latest" to "Anime in Corso",
        "$mainUrl/api/archive?status=upcoming&sort_by=latest" to "Prossime Uscite",
        "$mainUrl/api/calendar" to "Calendario"
    )

    private suspend fun ensureSession() {
        if (sessionCookies.isNotEmpty() && xsrfToken.isNotEmpty()) return
        try {
            val res = app.get(mainUrl, timeout = 15)
            if (res.cookies.isNotEmpty()) {
                sessionCookies = res.cookies
                xsrfToken = res.cookies["XSRF-TOKEN"]?.let {
                    URLDecoder.decode(it, "UTF-8")
                } ?: ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore setup sessione: ${e.message}")
        }
    }

    private fun getApiHeaders(referer: String = "$mainUrl/"): Map<String, String> {
        val headers = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest"
        )
        if (xsrfToken.isNotEmpty()) {
            headers["X-XSRF-TOKEN"] = xsrfToken
        }
        return headers
    }

    /**
     * Ritorna true se l'anime ha SOLO la variante doppiata ("ita")
     * o se contiene tag ITA espliciti nel titolo/slug.
     */
    private fun isDubOnly(anime: Anime): Boolean {
        val title = (anime.title ?: anime.titleIt ?: anime.titleJa ?: "").lowercase()
        val slug = (anime.slug ?: "").lowercase()
        if (title.contains("(ita)") || slug.endsWith("-ita")) return true

        val variants = anime.variants ?: emptyList()
        // Se variants non è vuoto (es. gli upcoming hanno variants vuoto)
        // e tutte le varianti presenti sono "ita", allora la serie è esclusivamente Dub.
        if (variants.isNotEmpty() && variants.all { it.language.equals("ita", ignoreCase = true) }) {
            return true
        }
        return false
    }

    private fun getDisplayTitle(anime: Anime): String {
        return anime.title?.takeIf { it.isNotBlank() }
            ?: anime.titleEng?.takeIf { it.isNotBlank() }
            ?: anime.titleJa?.takeIf { it.isNotBlank() }
            ?: anime.titleIt?.takeIf { it.isNotBlank() }
            ?: "Senza titolo"
    }

    private fun getAnimeUrl(anime: Anime): String {
        val slugPart = anime.slug?.takeIf { it.isNotBlank() } ?: "anime"
        return "$mainUrl/anime/${anime.id}-$slugPart"
    }

    private fun mapTvType(type: String?): TvType {
        return when (type?.lowercase()) {
            "movie", "film" -> TvType.AnimeMovie
            "ova", "ona", "special", "tv_short" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun mapShowStatus(status: String?): ShowStatus {
        return when (status?.lowercase()) {
            "ongoing", "in corso" -> ShowStatus.Ongoing
            "completed", "terminato" -> ShowStatus.Completed
            else -> ShowStatus.Ongoing
        }
    }

    private fun parseInertiaPage(doc: Document): InertiaPage? {
        val dataPage = doc.selectFirst("#app")?.attr("data-page") ?: return null
        return try {
            mapper.readValue<InertiaPage>(dataPage)
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing data-page: ${e.message}")
            null
        }
    }

    private fun Anime.toSearchResult(subEpisodeCount: Int? = null): AnimeSearchResponse? {
        if (this.id == 0 || isDubOnly(this)) return null
        val displayTitle = getDisplayTitle(this)
        val url = getAnimeUrl(this)

        val subVariantCount = this.variants
            ?.firstOrNull { !it.language.equals("ita", ignoreCase = true) }
            ?.episodesCount
        val epCount = subEpisodeCount ?: subVariantCount

        return newAnimeSearchResponse(displayTitle, url, mapTvType(this.type)) {
            this.posterUrl = bestPoster
            // Nessuna valutazione (score) e nessun badge Dub: solo numero episodio Sub se disponibile
            if (epCount != null && epCount > 0) {
                addDubStatus(dubExist = false, subExist = true, subEpisodes = epCount)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        val endpoint = request.data
        val isCalendar = endpoint.endsWith("/api/calendar")
        if (isCalendar && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val sep = if (endpoint.contains("?")) "&" else "?"
        val apiUrl = if (isCalendar) endpoint else "$endpoint${sep}page=$page"

        val items = mutableListOf<SearchResponse>()
        var hasNext = false

        try {
            val res = app.get(apiUrl, headers = getApiHeaders(), cookies = sessionCookies, timeout = 15)
            if (res.code == 200) {
                when {
                    endpoint.contains("/api/home/latest-episodes") -> {
                        val parsed = mapper.readValue<LatestEpisodesPage>(res.text)
                        hasNext = (parsed.currentPage ?: page) < (parsed.lastPage ?: 1)
                        parsed.data.orEmpty()
                            // Filtra via tutti gli episodi Dub ("ita")
                            .filter { !it.language.equals("ita", ignoreCase = true) }
                            .forEach { item ->
                                val anime = item.episode?.anime ?: return@forEach
                                val epNum = item.episode.number?.toDoubleOrNull()?.toInt()
                                anime.toSearchResult(subEpisodeCount = epNum)?.let { items.add(it) }
                            }
                    }

                    isCalendar -> {
                        val parsed = mapper.readValue>>(res.text)
                        parsed.values.flatten()
                            // Filtra via tutte le uscite Dub dal calendario
                            .filter { it.isDub != true }
                            .forEach { entry ->
                                val anime = entry.anime ?: return@forEach
                                val nextEp = entry.nextEpisodeNumber?.toDoubleOrNull()?.toInt()
                                anime.toSearchResult(subEpisodeCount = nextEp)?.let { items.add(it) }
                            }
                    }

                    else -> {
                        val parsed = mapper.readValue<ApiResponse<Anime>>(res.text)
                        hasNext = (parsed.currentPage ?: page) < (parsed.lastPage ?: 1)
                        parsed.data.orEmpty().forEach { anime ->
                            anime.toSearchResult()?.let { items.add(it) }
                        }
                    }
                }
            } else {
                // Fallback HTML Inertia se l'endpoint API non risponde 200
                val htmlUrl = apiUrl.replace("/api/home/latest-episodes", "")
                    .replace("/api/", "/")
                val doc = app.get(htmlUrl, cookies = sessionCookies, timeout = 15).document
                val props = parseInertiaPage(doc)?.props
                when {
                    endpoint.contains("latest-episodes") -> {
                        props?.latestEpisodes?.data.orEmpty()
                            .filter { !it.language.equals("ita", ignoreCase = true) }
                            .forEach { item ->
                                val anime = item.episode?.anime ?: return@forEach
                                val epNum = item.episode.number?.toDoubleOrNull()?.toInt()
                                anime.toSearchResult(subEpisodeCount = epNum)?.let { items.add(it) }
                            }
                    }
                    isCalendar -> {
                        val cal = props?.entries ?: props?.calendarEntries
                        cal?.values?.flatten().orEmpty()
                            .filter { it.isDub != true }
                            .forEach { entry ->
                                entry.anime?.toSearchResult()?.let { items.add(it) }
                            }
                    }
                    else -> {
                        props?.animes?.data.orEmpty().forEach { anime ->
                            anime.toSearchResult()?.let { items.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore getMainPage ($apiUrl): ${e.message}")
        }

        val uniqueItems = items.distinctBy { it.url }
        return newHomePageResponse(request.name, uniqueItems, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ensureSession()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/api/search?q=$encoded&title=$encoded"
        val results = mutableListOf<SearchResponse>()

        try {
            val res = app.get(searchUrl, headers = getApiHeaders(), cookies = sessionCookies, timeout = 15)
            if (res.code == 200) {
                val parsed = mapper.readValue<ApiResponse<Anime>>(res.text)
                parsed.data.orEmpty().forEach { anime ->
                    anime.toSearchResult()?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore search ($query): ${e.message}")
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun fetchAllAnimeEpisodes(animeId: Int, initialLoaded: LoadedEpisodes?): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        val firstBatch = initialLoaded?.episodes.orEmpty()
        allEpisodes.addAll(firstBatch)

        val total = initialLoaded?.total ?: firstBatch.size
        var currentStart = (initialLoaded?.start ?: 1) + firstBatch.size

        while (allEpisodes.size < total && firstBatch.isNotEmpty()) {
            val endRange = minOf(currentStart + 119, total)
            val epApiUrl = "$mainUrl/api/anime/$animeId/episodes?start_range=$currentStart&end_range=$endRange"
            try {
                val res = app.get(epApiUrl, headers = getApiHeaders(), cookies = sessionCookies, timeout = 15)
                if (res.code != 200) break
                val node = mapper.readTree(res.text)
                val epArrayNode = when {
                    node.isArray -> node
                    node.has("episodes") -> node.get("episodes")
                    node.has("data") -> node.get("data")
                    else -> null
                } ?: break

                val batch: List<Episode> = mapper.convertValue(
                    epArrayNode,
                    mapper.typeFactory.constructCollectionType(List::class.java, Episode::class.java)
                )
                if (batch.isEmpty()) break
                allEpisodes.addAll(batch)
                currentStart += batch.size
            } catch (e: Exception) {
                Log.e(TAG, "Errore paginazione episodi ($animeId): ${e.message}")
                break
            }
        }
        return allEpisodes.distinctBy { it.id }
    }

    override suspend fun load(url: String): LoadResponse? {
        ensureSession()
        val cleanUrl = url.substringBefore("?")
        // Se l'URL proviene da un episodio specifico (/anime/id-slug/videoId), risale alla pagina principale dell'anime
        val animePageUrl = cleanUrl.replace(Regex("""/(\d+-[^/]+)/\d+$"""), "/$1")

        val doc = app.get(animePageUrl, cookies = sessionCookies, timeout = 15).document
        val inertia = parseInertiaPage(doc)
        val props = inertia?.props
        val anime = props?.anime ?: return null

        val rawEpisodes = fetchAllAnimeEpisodes(anime.id, props.loadedEpisodes)

        // Costruisce SOLO gli episodi Sub (scarta qualsiasi video con language == "ita")
        val subEpisodes = rawEpisodes.mapNotNull { ep ->
            val subVideo = ep.videos?.firstOrNull { !it.language.equals("ita", ignoreCase = true) }
            // Se l'episodio ha un array videos ma contiene solo "ita", lo scartiamo
            if (!ep.videos.isNullOrEmpty() && subVideo == null) {
                return@mapNotNull null
            }

            val videoId = subVideo?.id ?: ep.id
            val epNumberStr = ep.number ?: ep.seqNumberStart?.toString() ?: "1"
            val epNumberInt = epNumberStr.toDoubleOrNull()?.toInt()
            val epUrl = "$mainUrl/anime/${anime.id}-${anime.slug ?: "anime"}/$videoId"

            newEpisode(epUrl) {
                this.name = "Episodio $epNumberStr"
                this.episode = epNumberInt
                this.runTime = (subVideo?.runtime ?: anime.episodeRuntime?.times(60))?.div(60)
            }
        }

        val recommendations = (props.relatedAnimes.orEmpty() + props.recommendedAnimes.orEmpty())
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val subStatus = anime.variants
            ?.firstOrNull { !it.language.equals("ita", ignoreCase = true) }
            ?.status ?: anime.defaultStatus ?: anime.status

        return newAnimeLoadResponse(
            getDisplayTitle(anime),
            animePageUrl,
            mapTvType(anime.type)
        ) {
            this.posterUrl = anime.bestPoster
            this.backgroundPosterUrl = anime.bestBanner
            this.plot = anime.plot
            this.year = anime.year
            this.showStatus = mapShowStatus(subStatus)
            this.tags = anime.genres?.mapNotNull { it.name }.orEmpty()
            this.recommendations = recommendations

            // Solo lista episodi Sub (nessun DubStatus.Dubbed)
            addEpisodes(DubStatus.Subbed, subEpisodes)

            // NOTA: Non aggiungiamo score/addScore né addAniListId/addMalId,
            // così Cloudstream (SYNCVM) non sovrascrive la pagina mostrando le valutazioni esterne.
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureSession()
        try {
            val doc = app.get(data, cookies = sessionCookies, timeout = 15).document
            val props = parseInertiaPage(doc)?.props

            val embedUrl = props?.embedUrl
                ?: doc.selectFirst("iframe[src*=vixcloud], iframe[src*=scws]")?.attr("src")

            if (!embedUrl.isNullOrBlank()) {
                Log.d(TAG, "loadLinks target=$data -> sourceUrl=$embedUrl")
                VixCloudExtractor().getUrl(
                    url = embedUrl,
                    referer = "$mainUrl/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore loadLinks ($data): ${e.message}")
        }
        return false
    }
}
