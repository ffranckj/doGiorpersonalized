package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class InertiaPage(
    @JsonProperty("component") val component: String? = null,
    @JsonProperty("props") val props: InertiaProps? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("version") val version: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InertiaProps(
    @JsonProperty("carouselAnimes") val carouselAnimes: List<Anime>? = null,
    @JsonProperty("latestAnimes") val latestAnimes: List<Anime>? = null,
    @JsonProperty("upcomingAnimes") val upcomingAnimes: List<Anime>? = null,
    @JsonProperty("latestEpisodes") val latestEpisodes: LatestEpisodesPage? = null,
    @JsonProperty("topAnimes") val topAnimes: TopAnimesGroup? = null,
    @JsonProperty("animes") val animes: ApiResponse<Anime>? = null,
    @JsonProperty("entries") val entries: Map<String, List<CalendarEntry>>? = null,
    @JsonProperty("calendarEntries") val calendarEntries: Map<String, List<CalendarEntry>>? = null,
    @JsonProperty("anime") val anime: Anime? = null,
    @JsonProperty("episode") val episode: Episode? = null,
    @JsonProperty("video") val video: VideoItem? = null,
    @JsonProperty("loadedEpisodes") val loadedEpisodes: LoadedEpisodes? = null,
    @JsonProperty("relatedAnimes") val relatedAnimes: List<Anime>? = null,
    @JsonProperty("recommendedAnimes") val recommendedAnimes: List<Anime>? = null,
    @JsonProperty("embedUrl") val embedUrl: String? = null,
    @JsonProperty("scws_url") val scwsUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TopAnimesGroup(
    @JsonProperty("day") val day: List<Anime>? = null,
    @JsonProperty("week") val week: List<Anime>? = null,
    @JsonProperty("month") val month: List<Anime>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiResponse<T>(
    @JsonProperty("current_page") val currentPage: Int? = null,
    @JsonProperty("data") val data: List<T>? = emptyList(),
    @JsonProperty("last_page") val lastPage: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LatestEpisodesPage(
    @JsonProperty("current_page") val currentPage: Int? = null,
    @JsonProperty("data") val data: List<LatestEpisodeItem>? = emptyList(),
    @JsonProperty("last_page") val lastPage: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LatestEpisodeItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("scws_id") val scwsId: Int? = null,
    @JsonProperty("episode") val episode: LatestEpisodeInfo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LatestEpisodeInfo(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("number") val number: String? = null,
    @JsonProperty("anime") val anime: Anime? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CalendarEntry(
    @JsonProperty("anime") val anime: Anime? = null,
    @JsonProperty("isDub") val isDub: Boolean? = null,
    @JsonProperty("nextEpisodeNumber") val nextEpisodeNumber: String? = null,
    @JsonProperty("releaseTime") val releaseTime: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Anime(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("title_ja") val titleJa: String? = null,
    @JsonProperty("title_it") val titleIt: String? = null,
    @JsonProperty("title_eng") val titleEng: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("episode_runtime") val episodeRuntime: Int? = null,
    @JsonProperty("default_status") val defaultStatus: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("is_adult") val isAdult: Boolean? = null,
    @JsonProperty("images") val images: AnimeImages? = null,
    @JsonProperty("imageurl") val imageUrl: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("variants") val variants: List<AnimeVariant>? = emptyList(),
    @JsonProperty("genres") val genres: List<Genre>? = emptyList(),
    @JsonProperty("studios") val studios: List<Studio>? = emptyList()
) {
    val bestPoster: String?
        get() = images?.poster?.takeIf { it.isNotBlank() }
            ?: images?.cover?.takeIf { it.isNotBlank() }
            ?: imageUrl?.takeIf { it.isNotBlank() }

    val bestBanner: String?
        get() = images?.banner?.takeIf { it.isNotBlank() }
            ?: images?.cover?.takeIf { it.isNotBlank() }
            ?: cover?.takeIf { it.isNotBlank() }
            ?: bestPoster
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeImages(
    @JsonProperty("banner") val banner: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("logo") val logo: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeVariant(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("airing_day") val airingDay: String? = null,
    @JsonProperty("episodes_count") val episodesCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Studio(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoadedEpisodes(
    @JsonProperty("episodes") val episodes: List<Episode>? = emptyList(),
    @JsonProperty("start") val start: Int? = null,
    @JsonProperty("tab_size") val tabSize: Int? = null,
    @JsonProperty("total") val total: Int? = null,
    @JsonProperty("offset") val offset: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("number") val number: String? = null,
    @JsonProperty("seq_number_start") val seqNumberStart: Int? = null,
    @JsonProperty("seq_number_end") val seqNumberEnd: Int? = null,
    @JsonProperty("is_filler") val isFiller: Boolean? = null,
    @JsonProperty("scws_id") val scwsId: Int? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("videos") val videos: List<VideoItem>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VideoItem(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("scws_id") val scwsId: Int? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null
)
