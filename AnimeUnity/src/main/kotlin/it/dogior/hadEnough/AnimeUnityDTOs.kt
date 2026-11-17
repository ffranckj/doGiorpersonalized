package it.dogior.hadEnough

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
    val dubbed: Int = 1,
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

data class ApiResponse(
    @JsonProperty("records") val titles: List<Anime>? = emptyList(),
    @JsonProperty("tot") val total: Int = 0
)

data class Anime(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("imageurl") val imageUrl: String? = null,
    @JsonProperty("date") val date: String = "",
    @JsonProperty("episodes_count") val episodesCount: Int = 0,
    @JsonProperty("episodes_length") val episodesLength: Int = 0,
    @JsonProperty("status") val status: String = "",
    @JsonProperty("type") val type: String = "TV",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("title_eng") val titleEng: String? = null,
    @JsonProperty("day") val day: String? = null,
    @JsonProperty("dub") val dub: Int = 0,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("title_it") val titleIt: String? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("episodes") val episodes: List<Episode>? = emptyList(),
    @JsonProperty("genres") val genres: List<Genre>? = emptyList()
)

data class Episode(
    @JsonProperty("id") val id: Int,
    @JsonProperty("anime_id") val animeId: Int = 0,
    @JsonProperty("user_id") val userId: Int? = null,
    @JsonProperty("number") val number: String = "1",
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("visite") val visite: Int = 0,
    @JsonProperty("hidden") val hidden: Int = 0,
    @JsonProperty("public") val isPublic: Int = 0,
    @JsonProperty("scws_id") val scwsId: Int? = null,
    @JsonProperty("file_name") val fileName: String? = null
)

data class LatestEpisodesPage(
    @JsonProperty("current_page") val currentPage: Int = 1,
    @JsonProperty("data") val episodes: List<LatestEpisodeItem> = emptyList()
)

data class LatestEpisodeItem(
    @JsonProperty("id") val id: Int,
    @JsonProperty("anime_id") val animeId: Int = 0,
    @JsonProperty("user_id") val userId: Int? = null,
    @JsonProperty("number") val number: String = "1",
    @JsonProperty("anime") val anime: LatestEpisodeAnime
)

data class LatestEpisodeAnime(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("imageurl") val imageUrl: String? = null,
    @JsonProperty("episodes_count") val episodesCount: Int = 0,
    @JsonProperty("type") val type: String = "TV",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("title_eng") val titleEng: String? = null,
    @JsonProperty("dub") val dub: Int = 0,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("title_it") val titleIt: String? = null
)

data class AnimeInfo(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episodes_count") val episodesCount: Int = 0,
    @JsonProperty("current_episode") val currentEpisode: Int = 0,
    @JsonProperty("episodes") val episodes: List<Episode> = emptyList()
)

data class Genre(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String
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

data class AnilistResponse(
    @JsonProperty("data") val data: AnilistData
)

data class AnilistData(
    @JsonProperty("Media") val media: AnilistMedia
)

data class AnilistMedia(
    @JsonProperty("id") val id: Int,
    @JsonProperty("coverImage") val coverImage: AnilistCoverImage?,
    @JsonProperty("duration") val duration: Int?,
    @JsonProperty("trailer") val trailer: AnilistTrailer?
)

data class AnilistCoverImage(
    @JsonProperty("medium") val medium: String?,
    @JsonProperty("large") val large: String?,
    @JsonProperty("extraLarge") val extraLarge: String?
)

data class AnilistTrailer(
    @JsonProperty("id") val id: String?,
    @JsonProperty("site") val site: String?,
    @JsonProperty("thumbnail") val thumbnail: String?
)

data class JikanFullResponse(
    @JsonProperty("data") val data: JikanAnimeData
)

data class JikanAnimeData(
    @JsonProperty("trailer") val trailer: JikanTrailer?
)

data class JikanSearchResponse(
    @JsonProperty("data") val data: List<JikanSearchAnime> = emptyList()
)

data class JikanSearchAnime(
    @JsonProperty("title") val title: String,
    @JsonProperty("title_english") val titleEnglish: String? = null,
    @JsonProperty("title_japanese") val titleJapanese: String? = null,
    @JsonProperty("title_synonyms") val titleSynonyms: List<String>? = emptyList(),
    @JsonProperty("trailer") val trailer: JikanTrailer? = null
)

data class JikanTrailer(
    @JsonProperty("youtube_id") val youtubeId: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null
)
