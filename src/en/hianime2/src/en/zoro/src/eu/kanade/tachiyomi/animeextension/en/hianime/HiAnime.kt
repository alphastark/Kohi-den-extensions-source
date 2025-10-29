package eu.kanade.tachiyomi.animeextension.en.hianime

import eu.kanade.tachiyomi.animeextension.en.hianime.filters.HiAnimeFilters
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
// import eu.kanade.tachiyomi.network.POST // Uncomment if POST requests are needed later
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException // Import IOException for specific error handling

class HiAnime : AnimeHttpSource() {

    override val name = "HiAnime"

    override val baseUrl = "https://hianime.to"

    override val lang = "en"

    override val supportsLatest = true

    // Use Cloudflare interceptor
    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/most-popular?page=$page")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        // Add specific try-catch for network/parsing errors
        return try {
            val document = response.asJsoup()
            val animes = document.select("div.flw-item").mapNotNull { element ->
                // Use safe calls (?) and handle potential nulls
                val anchor = element.selectFirst("a") ?: return@mapNotNull null
                val title = element.selectFirst("h3.film-name a")?.attr("title") ?: return@mapNotNull null
                val thumb = element.selectFirst("img.film-poster-img")?.attr("data-src") ?: return@mapNotNull null

                SAnime.create().apply {
                    setUrlWithoutDomain(anchor.attr("href"))
                    this.title = title
                    thumbnail_url = thumb
                }
            }
            // Pagination logic (consistent with Zoro-like patterns)
            val hasNextPage = document.selectFirst("ul.pagination li.page-item a[title=Next]") != null
            AnimesPage(animes, hasNextPage)
        } catch (e: IOException) { // Network errors
            throw Exception("Network error fetching popular anime: ${e.message}")
        } catch (e: Exception) { // Parsing errors
            throw Exception("Error parsing popular anime page: ${e.message}")
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        // Extract ID robustly
        val animeId = anime.url.substringAfterLast("-", "").substringBefore("?").ifEmpty {
            throw Exception("Could not get anime ID from URL: ${anime.url}")
        }
        val ajaxUrl = "$baseUrl/ajax/v2/episode/list/$animeId"
        return GET(ajaxUrl, headers) // Use source headers
    }

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return client.newCall(episodeListRequest(anime))
            .asObservableSuccess()
            .map { response ->
                try {
                    val responseBody = response.body.string()
                    // More specific JSON parsing error
                    val result = try {
                         json.decodeFromString<EpisodeListDto>(responseBody)
                    } catch (e: Exception) {
                        throw Exception("Failed to parse episode list JSON: ${e.message}")
                    }

                    if (!result.status) {
                        throw Exception("Failed to fetch episode list: Server returned status false")
                    }
                    // Parse the HTML returned within the JSON
                    val document = Jsoup.parse(result.html)
                    parseEpisodesFromAjax(document)
                } catch (e: IOException) {
                     throw Exception("Network error fetching episode list: ${e.message}")
                } catch (e: Exception) { // Catch other parsing errors
                    throw Exception("Error parsing episode list: ${e.message}")
                }
            }
    }

    private fun parseEpisodesFromAjax(document: Document): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        // Select episode items
        document.select("div.ss-list > a.ssl-item.ep-item").forEach { ep ->
            val episode = SEpisode.create()
            val href = ep.attr("href")
            val title = ep.attr("title") // E.g., "Episode 1"
            val epNumStr = ep.selectFirst("div.ssli-order")?.text()

            if (href.isNullOrEmpty() || title.isNullOrEmpty() || epNumStr.isNullOrEmpty()) {
                println("Skipping episode due to missing data: href=$href, title=$title, num=$epNumStr")
                return@forEach // Skip this iteration if essential data is missing
            }

            episode.setUrlWithoutDomain(href)
            episode.name = title
            episode.episode_number = epNumStr.toFloatOrNull() ?: 0f
            // Keep the full URL initially to extract episode ID later in fetchVideoList
            episode.url = href
            episodeList.add(episode)
        }
        if (episodeList.isEmpty()) {
            // Check if there are elements but parsing failed, or if the list is genuinely empty
            if (document.selectFirst("div.ss-list > a.ssl-item.ep-item") != null) {
                 println("Episode elements found but parsing resulted in empty list.")
            } else {
                 println("No episode elements found in AJAX response.")
                 // Optionally throw an exception if an empty list is unexpected
                 // throw Exception("No episodes found for this anime.")
            }
        }
        return episodeList.reversed() // Typically episodes are listed newest first
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Not used as fetchEpisodeList is overridden.")
    }

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        // Extract episode ID robustly
        val episodeId = episode.url.substringAfterLast("ep=", "").substringBefore("?").ifEmpty {
            // Throw error immediately if ID is missing, needed for API call
            return Observable.error(Exception("Could not get episode ID from URL: ${episode.url}"))
        }
        val ajaxServersUrl = "$baseUrl/ajax/v2/episode/servers?episodeId=$episodeId"

        return client.newCall(GET(ajaxServersUrl, headers))
            .asObservableSuccess()
            .map { response ->
                val serverListHtml = try {
                    val responseBody = response.body.string()
                    val result = json.decodeFromString<EpisodeServersDto>(responseBody)
                    if (!result.status) throw Exception("Server list request returned status false")
                    result.html
                } catch (e: Exception) {
                    // Throw user-friendly error
                    throw Exception("Could not fetch server list: ${e.message}")
                }

                val serverListDocument = Jsoup.parse(serverListHtml)
                val videos = mutableListOf<Video>()
                val subtitles = mutableListOf<Track>()
                val handledSourceUrls = mutableSetOf<String>() // Avoid duplicate M3U8s

                fun processServerList(serverElements: org.jsoup.select.Elements, typeSuffix: String) {
                    serverElements.forEach { serverItem ->
                        val serverId = serverItem.attr("data-id")
                        val serverName = serverItem.selectFirst("a")?.text() ?: "Server $serverId"

                        if (serverId.isEmpty()) return@forEach // Skip if server ID is missing

                        val ajaxSourcesUrl = "$baseUrl/ajax/v2/episode/sources?id=$serverId"
                        try {
                            val sourceResponseCall = client.newCall(GET(ajaxSourcesUrl, headers))
                            val sourceResponse = sourceResponseCall.execute() // Execute synchronously within map
                            if (!sourceResponse.isSuccessful) {
                                println("Failed source request for server $serverId ($serverName): ${sourceResponse.code}")
                                return@forEach // Continue to next server
                            }

                            val sourceData = try {
                                json.decodeFromString<SourceDto>(sourceResponse.body.string())
                            } catch (e: Exception) {
                                println("Failed to parse JSON for server $serverId ($serverName): ${e.message}")
                                return@forEach
                            }

                            if (!sourceData.encrypted) {
                                sourceData.sources.firstOrNull()?.let { source ->
                                    if (source.file.isNotEmpty() && handledSourceUrls.add(source.file)) { // Add returns true if new
                                        if (source.type == "hls" && source.file.endsWith(".m3u8")) {
                                            try {
                                                videos.addAll(
                                                    videosFromHlsUrl(source.file, "$serverName $typeSuffix", headers)
                                                )
                                            } catch (hlsError: Exception) {
                                                println("Error processing HLS for $serverName: ${hlsError.message}")
                                                // Optionally add master URL as fallback even on error
                                                // videos.add(Video(source.file, "$serverName $typeSuffix - Master (Error)", source.file))
                                            }
                                        } else if (source.type == "mp4") {
                                            videos.add(Video(source.file, "$serverName $typeSuffix - MP4", source.file))
                                        }
                                    }
                                }
                                // Collect subtitles regardless of source duplication
                                subtitles.addAll(
                                    sourceData.tracks
                                        .filter { it.kind == "captions" && !it.file.isNullOrEmpty() && it.file.endsWith(".vtt") }
                                        .mapNotNull { track ->
                                            Track(track.file, track.label ?: "Subtitle")
                                        }
                                )
                            } else {
                                // TODO: Handle encrypted sources if they appear in testing
                                println("Skipping encrypted source for server $serverId ($serverName)")
                            }
                        } catch (e: IOException) {
                            println("Network error fetching sources for server $serverId ($serverName): ${e.message}")
                        } catch (e: Exception) { // Catch other errors during source processing
                            println("Error processing sources for server $serverId ($serverName): ${e.message}")
                        }
                    }
                }

                // Process SUB servers first (usually preferred)
                processServerList(serverListDocument.select("div.servers-sub div.item"), "SUB")
                // Uncomment to process DUB servers if needed
                // processServerList(serverListDocument.select("div.servers-dub div.item"), "DUB")

                if (videos.isEmpty()) {
                    // Provide a clearer error if no sources worked
                    throw Exception("No playable video sources found. The episode might be unavailable or encrypted.")
                }

                // Add unique subtitles to all found videos
                val uniqueSubtitles = subtitles.distinctBy { it.url }
                return@map videos.map { video ->
                    video.copy(subtitleTracks = uniqueSubtitles)
                }
            }
    }

    private fun videosFromHlsUrl(m3u8Url: String, prefix: String, headers: Headers): List<Video> {
        return try {
            val masterPlaylist = client.newCall(GET(m3u8Url, headers)).execute().body.string()
            val videoList = mutableListOf<Video>()

            // Get base URL correctly
             val masterBaseUri = m3u8Url.toHttpUrlOrNull()?.newBuilder()?.apply {
                 encodedPathSegments.lastOrNull()?.takeIf { it.contains(".") }?.let { fileName ->
                     removePathSegment(encodedPathSegments.size - 1)
                 }
             }?.build() ?: throw Exception("Invalid M3U8 URL: $m3u8Url")


            masterPlaylist.lines().forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract Resolution and Bandwidth more robustly
                    val resolution = line.substringAfter("RESOLUTION=", "").substringBefore(",").ifBlank { null }
                    val bandwidth = line.substringAfter("BANDWIDTH=", "").substringBefore(",").ifBlank { null }

                    val qualityLabel = when {
                        resolution != null -> resolution.substringAfter("x", "").trim() + "p"
                        bandwidth != null -> {
                            val bwNum = bandwidth.toLongOrNull()
                            when {
                                bwNum == null -> "Quality ${videoList.size + 1}"
                                bwNum >= 1500000 -> "1080p" // Example bandwidth mapping
                                bwNum >= 800000 -> "720p"
                                bwNum >= 350000 -> "480p"
                                else -> "360p"
                            }
                        }
                        else -> "Quality ${videoList.size + 1}" // Fallback if neither exists
                    }

                    // Get the relative URL from the next line
                    val streamUrlRelative = masterPlaylist.lines().getOrNull(index + 1)?.trim()
                    if (streamUrlRelative != null && !streamUrlRelative.startsWith("#")) {
                         // Resolve relative URL against the calculated base URI
                         val streamHttpUrl = try {
                             masterBaseUri.resolve(streamUrlRelative)
                         } catch (e: IllegalArgumentException) {
                             println("Failed to resolve relative URL: $streamUrlRelative against base: $masterBaseUri")
                             null // Skip if resolution fails
                         }

                        if (streamHttpUrl != null) {
                            videoList.add(Video(streamHttpUrl.toString(), "$prefix - $qualityLabel", streamHttpUrl.toString()))
                        }
                    }
                }
            }

            if (videoList.isEmpty()) {
                // If only master found or parsing failed somehow, add master
                videoList.add(Video(m3u8Url, "$prefix - Default", m3u8Url))
            }
             // Sort by quality (descending)
             videoList.sortByDescending {
                 it.quality.substringBefore("p").toIntOrNull() ?: 0
             }
            videoList

        } catch (e: IOException) {
             throw Exception("Network error fetching HLS playlist ($m3u8Url): ${e.message}")
        } catch (e: Exception) {
             // More specific parsing error
             throw Exception("Error parsing HLS playlist ($m3u8Url): ${e.message}")
        }
    }


    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used")
    }

    override fun videoUrlParse(video: Video): String {
        return video.url // URL is already the playable stream URL
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
         return try {
             val document = response.asJsoup()
             SAnime.create().apply {
                 title = document.selectFirst("h2.film-name")?.text()
                     ?: throw Exception("Could not find anime title")
                 thumbnail_url = document.selectFirst("img.film-poster-img")?.attr("src")
                 genre = document.select("div.anisc-info div.item-list a[href*=genre]").joinToString { it.text() }
                 description = document.selectFirst("div.film-description div.text")?.text()
                 // Robustly parse status and other details
                 document.select("div.anisc-info div.item").forEach { element ->
                     val heading = element.selectFirst("span.item-head")?.text()?.trim() ?: ""
                     val valueElement = element.selectFirst("span.name, a") // Check both span and a
                     val value = valueElement?.text()?.trim() ?: ""
                     when (heading) {
                         "Status:" -> status = parseStatus(value)
                         // Add other details if needed (e.g., Author, Studio)
                     }
                 }
             }
         } catch (e: Exception) {
             throw Exception("Failed to parse anime details: ${e.message}")
         }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "finished airing", "completed" -> SAnime.COMPLETED
            "currently airing", "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // =============================== Search ===============================
    // Uses fetchSearchAnime for simplicity with filters

     override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
         // Apply filters to get search parameters
         val params = HiAnimeFilters.getSearchParameters(filters)
         val request = searchAnimeRequest(page, query, params) // Pass params
         return client.newCall(request)
             .asObservableSuccess()
             .map { response ->
                 // Reuse popular parse logic, but catch specific search errors
                 try {
                    searchAnimeParse(response) // Use specific search parse if needed later
                 } catch (e: IOException) {
                     throw Exception("Network error during search: ${e.message}")
                 } catch (e: Exception) {
                     throw Exception("Error parsing search results: ${e.message}")
                 }
             }
     }

     // Keep this override, required by interface, but logic is in fetchSearchAnime
     override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
          val params = HiAnimeFilters.getSearchParameters(filters)
          return searchAnimeRequest(page, query, params) // Delegate
     }

    // Internal function that accepts SearchParameters
    private fun searchAnimeRequest(page: Int, query: String, params: HiAnimeFilters.SearchParameters): Request {
        val urlBuilder = "$baseUrl/filter".toHttpUrlOrNull()?.newBuilder()
            ?: throw Exception("Invalid base URL") // Should not happen

        urlBuilder.addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
        // Add filters only if they are not the default "all" value
        if (params.type != "all") urlBuilder.addQueryParameter("type", params.type)
        if (params.status != "all") urlBuilder.addQueryParameter("status", params.status)
        if (params.rated != "all") urlBuilder.addQueryParameter("rated", params.rated)
        if (params.score != "all") urlBuilder.addQueryParameter("score", params.score)
        if (params.season != "all") urlBuilder.addQueryParameter("season", params.season)
        if (params.language != "all") urlBuilder.addQueryParameter("language", params.language)
        if (params.sort != "default") urlBuilder.addQueryParameter("sort", params.sort)
        if (params.genres.isNotEmpty()) urlBuilder.addQueryParameter("genres", params.genres)

        return GET(urlBuilder.build().toString(), headers)
    }

    // searchAnimeParse can often reuse popularAnimeParse if the HTML structure is identical
    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response) // Assumes search results use the same item structure
    }

    override fun getFilterList(): AnimeFilterList = HiAnimeFilters.FILTER_LIST

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/home?page=$page")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return try {
            val document = response.asJsoup()
            // Try to find the specific "Latest" section, fall back to whole document
            val latestSection = document.select("section#main-content div.block_area_home section:has(h2:contains(Latest Update), h2:contains(Latest Episode))").firstOrNull() ?: document
            val animes = latestSection.select("div.flw-item").mapNotNull { element ->
                val anchor = element.selectFirst("a") ?: return@mapNotNull null
                val title = element.selectFirst("h3.film-name a")?.attr("title") ?: return@mapNotNull null
                val thumb = element.selectFirst("img.film-poster-img")?.attr("data-src") ?: return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(anchor.attr("href"))
                    this.title = title
                    thumbnail_url = thumb
                }
            }
            val hasNextPage = document.selectFirst("ul.pagination li.page-item a[title=Next]") != null
            AnimesPage(animes, hasNextPage)
        } catch (e: IOException) {
             throw Exception("Network error fetching latest updates: ${e.message}")
        } catch (e: Exception) {
            throw Exception("Error parsing latest updates page: ${e.message}")
        }
    }

    // ============================= Utilities ==============================
    private inline fun <reified T> parseJson(jsonString: String): T {
         try {
             // Ignore unknown keys to prevent crashes if API adds new fields
             val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
             return lenientJson.decodeFromString(jsonString)
         } catch (e: Exception) {
             // Provide more context in the error
             throw Exception("Failed to parse JSON: ${e.message}. JSON: ${jsonString.take(100)}...")
         }
    }

    // Data Transfer Objects (DTOs) for JSON parsing
    @Serializable
    data class EpisodeListDto(val status: Boolean = false, val html: String = "")

    @Serializable
    data class EpisodeServersDto(val status: Boolean = false, val html: String = "")

    @Serializable
    data class SourceDto(
        val sources: List<SourceItem> = emptyList(),
        val tracks: List<TrackItem> = emptyList(),
        val encrypted: Boolean = false, // Default to false if missing
        val server: Int? = null,
    )

    @Serializable
    data class SourceItem(val file: String = "", val type: String = "")

    @Serializable
    data class TrackItem(
        val file: String? = null, // Make nullable for safety
        val kind: String = "",
        val label: String? = null,
        val default: Boolean? = false,
    )
}

