package it.dogior.hadEnough

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class VixCloudExtractor(
    private val sourceName: String = "VixCloud",
    private val displayName: String = "AnimeUnity",
) : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = true
    private var referer: String? = null
    private val headers = mutableMapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
    )

    private companion object {
        const val LOG_TAG = "VixCloudExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(LOG_TAG, "REFERER: $referer  URL: $url")
        this.referer = referer
        val playlistUrl = getPlaylistLink(url)

        Log.d(LOG_TAG, "FINAL URL: $playlistUrl")

        val streamHeaders = headers.toMutableMap().apply {
            put("Referer", url)
        }

        callback(
            newExtractorLink(
                source = sourceName,
                name = displayName,
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = streamHeaders
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        Log.d(LOG_TAG, "Item url: $url")

        val rawScript = getRawMasterPlaylistScript(url)
        val parsed = runCatching {
            val script = JSONObject(getSanitisedScript(rawScript))
            val masterPlaylist = script.getJSONObject("masterPlaylist")
            val masterPlaylistParams = masterPlaylist.getJSONObject("params")
            val token = masterPlaylistParams.getString("token")
            val expires = masterPlaylistParams.getString("expires")
            val playlistUrl = masterPlaylist.getString("url")
            val canPlayFHD = script.optBoolean("canPlayFHD", false)
            buildFinalPlaylistUrl(playlistUrl, token, expires, canPlayFHD)
        }.getOrElse {
            extractPlaylistViaRegex(rawScript)
        }

        Log.d(LOG_TAG, "Master Playlist URL: $parsed")
        return parsed
    }

    private fun buildFinalPlaylistUrl(
        playlistUrl: String,
        token: String,
        expires: String,
        canPlayFHD: Boolean,
    ): String {
        val params = "token=$token&expires=$expires"
        val basePlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else if ("?" in playlistUrl) {
            "$playlistUrl&$params"
        } else {
            "$playlistUrl?$params"
        }
        return if (canPlayFHD) {
            "$basePlaylistUrl&h=1"
        } else {
            basePlaylistUrl
        }
    }

    private fun extractPlaylistViaRegex(rawScript: String): String {
        val token = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""")
            .find(rawScript)?.groupValues?.get(1)
            ?: error("Missing VixCloud token")
        val expires = Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""")
            .find(rawScript)?.groupValues?.get(1)
            ?: error("Missing VixCloud expires")
        val playlistUrl = Regex("""url\s*:\s*['"](https?://[^'"]+)['"]""")
            .find(rawScript)?.groupValues?.get(1)
            ?: error("Missing VixCloud playlist url")
        val canPlayFHD = Regex("""window\.canPlayFHD\s*=\s*(true|1)""")
            .containsMatchIn(rawScript)

        return buildFinalPlaylistUrl(playlistUrl, token, expires, canPlayFHD)
    }

    private suspend fun getRawMasterPlaylistScript(url: String): String {
        Log.d(LOG_TAG, "Embed url: $url")

        val iframe = app.get(url, headers = headers, referer = this.referer).document

        val scripts = iframe.select("script")
        return scripts
            .firstOrNull { it.data().contains("masterPlaylist") }
            ?.data()
            ?.replace("\n", "\t")
            ?: error("Missing VixCloud masterPlaylist script")
    }

    private fun getSanitisedScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        return "{\n${jsonObjects.joinToString(",\n")}\n}"
            .replace("'", "\"")
    }
}
