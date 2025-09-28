package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.Qualities
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.Properties
import java.io.FileInputStream

// Assicurati che questa importazione sia corretta se usi DTO da un altro file
// import it.dogior.hadEnough.dto.* // Esempio se hai un package dto

class Torrentio : TmdbProvider() {
    private val torrentioUrl = "https://torrentio.strem.fun"
    override var mainUrl =
        "$torrentioUrl/providers=yts,eztv,rarbg,1337x,thepiratebay,kickasstorrents,torrentgalaxy,ilcorsaronero,magnetdl|sort=seeders|language=italian"
    override var name = "Torrentio"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Torrent)
    override var lang = "it"
    override val hasMainPage = true
    private val tmdbAPI = "https://api.themoviedb.org/3"
    private val TRACKER_LIST_URL = "https://newtrackon.com/api/stable"

    private val apiKey: String by lazy {
        val props = Properties()
        var keyFromProperties: String? = null
        val potentialPaths = listOf(
            "../secrets.properties",
            "secrets.properties",
            "../../secrets.properties"
        )

        for (path in potentialPaths) {
            try {
                FileInputStream(path).use { stream ->
                    props.load(stream)
                    keyFromProperties = props.getProperty("TMDB_API")
                    if (!keyFromProperties.isNullOrBlank()) {
                        Log.d("Torrentio", "TMDB API Key loaded successfully from '$path'")
                        return@lazy keyFromProperties!!
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                Log.w("Torrentio", "secrets.properties not found at '$path'")
            } catch (e: Exception) {
                // CORREZIONE per Log.e
                Log.e("Torrentio", "Error reading TMDB API Key from '$path': ${e.message}")
                break
            }
        }

        Log.e("Torrentio", "TMDB_API key not found in any attempted secrets.properties paths or file is not readable.")
        return@lazy "MISSING_TMDB_API_KEY" // Questa riga (96 nel tuo errore) dovrebbe essere corretta.
    }

    private val authHeaders by lazy {
        mapOf("Authorization" to "Bearer $apiKey")
    }

    private val today = getDate()
    private val tvFilters =
        "&language=it-IT&watch_region=IT&with_watch_providers=359|222|524|283|39|8|337|119|350"

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?language=it-IT" to "Di Tendenza",
        "$tmdbAPI/movie/now_playing?region=IT&language=it-IT" to "Ora al Cinema",
        "$tmdbAPI/discover/tv?air_date.gte=$today&air_date.lte=$today&sort_by=vote_average.desc$tvFilters" to "Serie in onda oggi",
        "$tmdbAPI/movie/popular?region=IT&language=it-IT" to "Film Popolari",
        "$tmdbAPI/discover/tv?vote_count.gte=100$tvFilters" to "Serie TV Popolari",
        "$tmdbAPI/movie/top_rated?region=IT&language=it-IT" to "Film per Valutazione",
        "$tmdbAPI/discover/tv?sort_by=vote_average.desc&vote_count.gte=100$tvFilters" to "Serie TV per Valutazione",
        "$tmdbAPI/discover/tv?with_networks=213&region=IT&language=it-IT" to "Netflix",
        "$tmdbAPI/discover/tv?with_networks=1024&region=IT&language=it-IT" to "Prime Video",
        "$tmdbAPI/discover/tv?with_networks=2739&region=IT&language=it-IT" to "Disney+",
        "$tmdbAPI/discover/tv?with_watch_providers=39&watch_region=IT&language=it-IT&without_watch_providers=359,110,222" to "Now TV",
        "$tmdbAPI/discover/tv?with_networks=2552&region=IT&language=it-IT" to "Apple TV+",
        "$tmdbAPI/discover/tv?with_networks=4330&region=IT&language=it-IT" to "Paramount+",
        "$tmdbAPI/discover/tv?with_watch_providers=283&watch_region=IT&language=it-IT" to "Crunchyroll",
        "$tmdbAPI/discover/tv?with_watch_providers=222&watch_region=IT&language=it-IT&without_watch_providers=359,110,39" to "RaiPlay",
        "$tmdbAPI/discover/tv?with_watch_providers=359|110&watch_region=IT&language=it-IT&without_watch_providers=39,222" to "Mediaset Infinity",
        "$tmdbAPI/discover/tv?with_watch_providers=524&watch_region=IT&language=it-IT&without_watch_providers=359,110,39,222" to "Discovery+",
    )

    private fun getDate(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        return formatter.format(calendar.time)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlRequest = "${request.data}&page=$page"
        Log.d("Torrentio", "Requesting TMDB URL: $urlRequest with key ending in ${apiKey.takeLast(4)}")
        val respBody = try {
            app.get(urlRequest, headers = authHeaders).body.string()
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Error fetching getMainPage from TMDB: ${e.message}")
            throw ErrorLoadingException("Failed to load data for ${request.name}")
        }
        Log.d("Torrentio", "TMDB Response for ${request.name} (first 300 chars): ${respBody.take(300)}")

        val parsedJson = try {
            parseJson<Results>(respBody)
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Failed to parse JSON for ${request.name}: ${e.message}")
            Log.d("Torrentio", "Problematic JSON Body: $respBody")
            throw ErrorLoadingException("Invalid JSON response for ${request.name}")
        }

        val home = parsedJson.results?.mapNotNull { media ->
            val type = if (request.data.contains("tv")) "tv" else "movie"
            media.toSearchResponse(type = type)
        }?.toMutableList()

        if (home == null) {
            Log.w("Torrentio", "Parsed results are null for ${request.name}")
            throw ErrorLoadingException("No results for ${request.name}")
        }
        if (home.isEmpty()){
            Log.w("Torrentio", "Parsed results are empty for ${request.name}")
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$tmdbAPI/search/multi?language=it-IT&query=$query&page=1&include_adult=true"
        Log.d("Torrentio", "Search TMDB URL: $searchUrl with key ending in ${apiKey.takeLast(4)}")

        val response = try {
            app.get(searchUrl, headers = authHeaders)
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Error fetching search from TMDB: ${e.message}")
            return null
        }
        val responseBodyString = try {
            response.body.string() // Leggi il corpo solo una volta
        } catch (e: Exception) {
            Log.e("Torrentio", "Error reading search response body: ${e.message}")
            return null
        }
        Log.d("Torrentio", "Search TMDB Response (status ${response.code}, first 300 chars): ${responseBodyString.take(300)}")

        return try {
            parseJson<Results>(responseBodyString).results?.mapNotNull { media ->
                media.toSearchResponse()
            }
        } catch (e: Exception) {
            Log.e("Torrentio", "Failed to parse search response JSON: ${e.message}")
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<Data>(url)
        val type = if (data.type == "movie") TvType.Movie else TvType.TvSeries
        val append = "alternative_titles,external_ids,keywords,videos"

        val resUrl = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?language=it-IT&append_to_response=$append"
        } else {
            "$tmdbAPI/tv/${data.id}?language=it-IT&append_to_response=$append"
        }
        Log.d("Torrentio", "Load TMDB URL: $resUrl with key ending in ${apiKey.takeLast(4)}")

        val res = try {
            app.get(resUrl, headers = authHeaders).parsedSafe<MediaDetail>()
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Error fetching load details from TMDB for ID ${data.id}: ${e.message}")
            throw ErrorLoadingException("Failed to load details for ID ${data.id}")
        }

        if (res == null) {
            Log.e("Torrentio", "Invalid Json Response or null from TMDB for ID ${data.id}. URL: $resUrl")
            throw ErrorLoadingException("Invalid Json Response from TMDB for ID ${data.id}")
        }

        val title = res.title ?: res.name ?: return null
        val poster = getImageUrl(res.posterPath, getOriginal = true)
        val bgPoster = getImageUrl(res.backdropPath, getOriginal = true)
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val genres = res.genres?.mapNotNull { it.name }
        val trailer = res.videos?.results?.filter { it.type == "Trailer" }
            ?.map { "https://www.youtube.com/watch?v=${it.key}" }?.reversed().orEmpty()
            .ifEmpty { res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" } }

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    data.id,
                    type = data.type,
                    title = title,
                    year = year,
                    imdbId = res.imdbId,
                    airedDate = res.releaseDate ?: res.firstAirDate,
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = genres
                addTrailer(trailer)
                addTMDbId(data.id.toString())
            }
        } else {
            val episodes = getEpisodes(res, data.id)
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = genres
                addTrailer(trailer)
                addTMDbId(data.id.toString())
            }
        }
    }

    private suspend fun getEpisodes(showData: MediaDetail, id: Int?): List<Episode> {
        val episodes = showData.seasons?.mapNotNull { season ->
            val seasonUrl = "$tmdbAPI/tv/${showData.id}/season/${season.seasonNumber}"
            Log.d("Torrentio", "Season TMDB URL: $seasonUrl with key ending in ${apiKey.takeLast(4)}")

            val seasonDetail = try {
                app.get(seasonUrl, headers = authHeaders).parsedSafe<MediaDetailEpisodes>()
            } catch (e: Exception) {
                // CORREZIONE per Log.e
                Log.e("Torrentio", "Error fetching season details from TMDB: ${e.message}")
                null
            }

            seasonDetail?.episodes?.map { ep ->
                newEpisode(
                    LinkData(
                        id,
                        type = "tv",
                        season = ep.seasonNumber,
                        episode = ep.episodeNumber,
                        epid = ep.id,
                        title = showData.name ?: showData.title,
                        year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                        epsTitle = ep.name,
                        date = ep.airDate,
                        imdbId = showData.imdbId ?: showData.externalIds?.imdbId
                    ).toJson()
                ) {
                    this.name = ep.name
                    this.season = ep.seasonNumber
                    this.episode = ep.episodeNumber
                    this.posterUrl = getImageUrl(ep.stillPath)
                    this.description = ep.overview
                }
            }
        }?.flatten()
        return episodes ?: emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val show = parseJson<LinkData>(data)
        var success = false
        val torrentioRequestUrl = if (show.season == null) {
            "$mainUrl/stream/movie/${show.imdbId}.json"
        } else {
            "$mainUrl/stream/series/${show.imdbId}:${show.season}:${show.episode}.json"
        }
        Log.d("Torrentio", "Torrentio Request URL: $torrentioRequestUrl")

        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        )

        val resBody = try {
            app.get(torrentioRequestUrl, headers = headers, timeout = 20000L).body.string()
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Error fetching from Torrentio: ${e.message}")
            return false
        }

        Log.d("Torrentio", "Torrentio Raw Response (first 300 chars): ${resBody.take(300)}")

        if (resBody.isBlank() || resBody.startsWith("<", ignoreCase = true)) {
            Log.e("Torrentio", "Torrentio returned HTML or empty response.")
            return false
        }

        val response = try {
            parseJson<TorrentioResponse>(resBody)
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Failed to parse Torrentio JSON response: ${e.message}")
            Log.e("Torrentio", "Problematic Torrentio JSON Body (first 500 chars): ${resBody.take(500)}")
            return false
        }

        response.streams.forEach { stream ->
            val formattedTitleName = stream.title
                ?.let { title ->
                    val tags = "\\[(.*?)]".toRegex().findAll(title)
                        .map { match -> "[${match.groupValues[1]}]" }
                        .joinToString(" | ")
                    val seeder = "👤\\s*(\\d+)".toRegex().find(title)?.groupValues?.get(1) ?: "0"
                    val provider =
                        "⚙️\\s*([^\\\\]+)".toRegex().find(title)?.groupValues?.get(1)?.trim()
                            ?: "Unknown"
                    "Torrentio | $tags | Seeder: $seeder | Provider: $provider".trim()
                }
            val magnet = generateMagnetLink(TRACKER_LIST_URL, stream.infoHash)
            if (magnet.isNotEmpty()) {
                success = true
                // CORREZIONE per newExtractorLink
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = formattedTitleName ?: stream.name ?: "Torrent Link",
                        url = magnet,
                        type = INFER_TYPE
                        // referer e quality verranno impostati nel blocco successivo se necessario
                    ).apply {
                        quality = getIndexQuality(stream.name)
                        // referer = "" // Imposta se necessario
                    }
                )
            }
        }
        return success
    }

    private suspend fun generateMagnetLink(trackerListUrl: String, hash: String?): String {
        if (hash.isNullOrBlank()) return ""

        val trackerResponseText = try {
            app.get(trackerListUrl).text
        } catch (e: Exception) {
            // CORREZIONE per Log.e
            Log.e("Torrentio", "Failed to get tracker list from $trackerListUrl: ${e.message}")
            null
        }

        val trackerList = trackerResponseText?.trim()?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

        return buildString {
            append("magnet:?xt=urn:btih:$hash")
            trackerList.forEach { tracker ->
                append("&tr=").append(tracker.trim())
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        if (str == null) return Qualities.Unknown.value
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getImageUrl(link: String?, getOriginal: Boolean = false): String? {
        if (link == null) return null
        val width = if (getOriginal) "original" else "w500"
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/$width$link" else link
    }

    // Assicurati che la classe Media (nel tuo DTO) abbia le proprietà:
    // mediaType: String?, title: String?, name: String?, originalTitle: String?, id: Int, posterPath: String?
    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        if (this.mediaType == "person") return null

        val itemType = when (this.mediaType ?: type) {
            "movie" -> TvType.Movie
            "tv" -> TvType.TvSeries
            else -> null
        } ?: return null

        return newMovieSearchResponse(
            this.title ?: this.name ?: this.originalTitle ?: return null,
            Data(id = this.id, type = this.mediaType ?: type).toJson(),
            itemType,
        ) {
            // Se ancora dà errore qui, VERIFICA LA TUA CLASSE Media nel DTO.
            // Deve avere una proprietà 'posterPath' (o come si chiama il campo JSON corrispondente)
            this.posterUrl = getImageUrl(posterPath)
        }
    }
}
