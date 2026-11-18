// === FILE: AnimeUnityDTOs.kt ===
package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class BooleanOrString {
    data class AsBoolean(val value: Boolean) : BooleanOrString() {
        override fun toString(): String {
            return value.toString()
        }
    }

    data class AsString(val value: String) : BooleanOrString()

    fun getValue(): Any {
        return when (this) {
            is AsBoolean -> this.value
            is AsString -> this.value
        }
    }
}

data class RequestData(
    val title: String = "",
    val type: BooleanOrString = BooleanOrString.AsBoolean(false),
    val year: BooleanOrString = BooleanOrString.AsBoolean(false),
    val orderBy: BooleanOrString = BooleanOrString.AsBoolean(false),
    val status: BooleanOrString = BooleanOrString.AsBoolean(false),
    val genres: Any = BooleanOrString.AsBoolean(false),
    val season: BooleanOrString = BooleanOrString.AsBoolean(false),
    var offset: Int? = 0,
    val dubbed: Int = 0,
) {
    private fun serializeValue(value: Any): Any {
        return when (value) {
            is BooleanOrString -> value.getValue()
            is List<*> -> JSONArray().apply {
                value.filterIsInstance<ArchiveGenreOption>().forEach { genre ->
                    put(
                        JSONObject().apply {
                            put("id", genre.id)
                            put("name", genre.name)
                        }
                    )
                }
            }
            else -> value
        }
    }

    private fun toJson(): JSONObject {
        val m = mapOf(
            "title" to title,
            "type" to serializeValue(type),
            "year" to serializeValue(year),
            "order" to serializeValue(orderBy),
            "status" to serializeValue(status),
            "genres" to serializeValue(genres),
            "season" to serializeValue(season),
            "dubbed" to dubbed,
            "offset" to offset
        )

        return JSONObject().apply {
            m.forEach { (key, value) ->
                put(key, value)
            }
        }
    }

    fun toRequestBody(): RequestBody {
        return this.toJson().toString()
            .toRequestBody("application/json;charset=utf-8".toMediaType())
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiResponse(
    @JsonProperty("records") val titles: List<Anime>? = emptyList(),
    @JsonProperty("tot") val total: Int = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Anime(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("imageurl") val imageUrl: String = "",
    @JsonProperty("plot") val plot: String = "",
    @JsonProperty("date") val date: String = "",
    @JsonProperty("episodes_count") val episodesCount: Int = 0,
    @JsonProperty("episodes_length") val episodesLength: Int = 0,
    @JsonProperty("status") val status: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("title_eng") val titleEng: String? = null,
    @JsonProperty("day") val day: String? = null,
    @JsonProperty("dub") val dub: Int = 0,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("title_it") val titleIt: String? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("episodes") val episodes: List<Episode>? = emptyList(),
    @JsonProperty("genres") val genres: List<Genre> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("anime_id") val animeId: Int = 0,
    @JsonProperty("user_id") val userId: Int? = null,
    @JsonProperty("number") val number: String = "",
    @JsonProperty("link") val link: String = "",
    @JsonProperty("visite") val visite: Int = 0,
    @JsonProperty("hidden") val hidden: Int = 0,
    @JsonProperty("public") val isPublic: Int = 0,
    @JsonProperty("scws_id") val scwsId: Int = 0,
    @JsonProperty("file_name") val fileName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LatestEpisodesPage(
    @JsonProperty("current_page") val currentPage: Int = 1,
    @JsonProperty("data") val episodes: List<LatestEpisodeItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LatestEpisodeItem(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("anime_id") val animeId: Int = 0,
    @JsonProperty("user_id") val userId: Int? = null,
    @JsonProperty("number") val number: String = "",
    @JsonProperty("anime") val anime: LatestEpisodeAnime
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LatestEpisodeAnime(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("imageurl") val imageUrl: String? = null,
    @JsonProperty("episodes_count") val episodesCount: Int = 0,
    @JsonProperty("type") val type: String = "",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("title_eng") val titleEng: String? = null,
    @JsonProperty("dub") val dub: Int = 0,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("title_it") val titleIt: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeInfo(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodes_count") val episodesCount: Int = 0,
    @JsonProperty("current_episode") val currentEpisode: Int = 0,
    @JsonProperty("episodes") val episodes: List<Episode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String = ""
)

data class ArchiveGenreOption(
    val id: Int,
    val name: String,
)

data class AdvancedSearchConfig(
    val enabled: Boolean,
    val title: String,
    val genre: ArchiveGenreOption?,
    val year: String?,
    val order: String?,
    val status: String?,
    val type: String?,
    val season: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistResponse(
    @JsonProperty("data") val data: AnilistData
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistData(
    @JsonProperty("Media") val media: AnilistMedia
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistMedia(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("coverImage") val coverImage: AnilistCoverImage? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("trailer") val trailer: AnilistTrailer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistCoverImage(
    @JsonProperty("medium") val medium: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("extraLarge") val extraLarge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnilistTrailer(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanFullResponse(
    @JsonProperty("data") val data: JikanAnimeData
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanAnimeData(
    @JsonProperty("trailer") val trailer: JikanTrailer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanSearchResponse(
    @JsonProperty("data") val data: List<JikanSearchAnime> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanSearchAnime(
    @JsonProperty("title") val title: String = "",
    @JsonProperty("title_english") val titleEnglish: String? = null,
    @JsonProperty("title_japanese") val titleJapanese: String? = null,
    @JsonProperty("title_synonyms") val titleSynonyms: List<String>? = emptyList(),
    @JsonProperty("trailer") val trailer: JikanTrailer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JikanTrailer(
    @JsonProperty("youtube_id") val youtubeId: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null
)
