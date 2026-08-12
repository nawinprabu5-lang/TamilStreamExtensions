package com.tamilstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class IsaiminiProvider : MainAPI() {
    // ═══════════════════════════════════════════════════════════
    // Provider metadata — change mainUrl if domain changes
    // ═══════════════════════════════════════════════════════════
    override var mainUrl = "https://isaimini.cfd"
    override var name = "Isaimini"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ═══════════════════════════════════════════════════════════
    // Homepage categories — each maps a URL path to a display name
    // ═══════════════════════════════════════════════════════════
    override val mainPage = mainPageOf(
        "" to "Latest Movies",
        "/bollywood-movies/bollywood-movies-new/" to "New Bollywood",
        "/bollywood-movies/bollwood-movies-new-hd/" to "Bollywood HD",
        "/hollywood-movies/hollywood-movies-new/" to "New Hollywood",
        "/hollywood-movies/hollywood-movies-new-hd/" to "Hollywood HD",
        "/south-indian-dubbed-movies-download/" to "South Hindi Dubbed",
        "/dual-audio-hindi-english-movies/" to "Dual Audio Hindi",
        "/web-series-hindi/" to "Web Series Hindi",
        "/tv-shows/" to "TV Shows",
        "/action/" to "Action",
        "/thriller/" to "Thriller",
        "/horror/" to "Horror",
        "/comedy/" to "Comedy",
        "/animation/" to "Animation",
    )

    // ═══════════════════════════════════════════════════════════
    // getMainPage: Fetches paginated category listings
    // Isaimini pagination: /page/{num}/ or {category}/page/{num}/
    // ═══════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            if (page == 1) mainUrl else "$mainUrl/page/$page/"
        } else {
            if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}page/$page/"
        }

        val doc = app.get(url, timeout = 30).document
        val home = doc.select("div.thumb").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(request.name, home),
            hasNext = doc.selectFirst("div.pagination a:last-child") != null
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Helper: Converts a <div class="thumb"> element to SearchResponse
    // Each thumb has: <a class="thumbtitle"> for title + <img> for poster
    // ═══════════════════════════════════════════════════════════
    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("a.thumbtitle") ?: return null
        val title = titleEl.text().trim()
        val href = titleEl.attr("href")
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }

        // Detect series from title (e.g. "Season 1", "EP - 03 Added")
        val isSeries = title.contains(Regex("(?i)(season|ep\\s*-|episode|s\\d{1,2})"))

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // search: POST-based search (DLE CMS uses POST, not GET)
    // Form: do=search, subaction=search, story={query}
    // ═══════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            mainUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            ),
            timeout = 30
        ).document

        return doc.select("div.thumb").mapNotNull { it.toSearchResult() }
    }

    // ═══════════════════════════════════════════════════════════
    // load: Fetches full movie/series details from detail page
    // Extracts: title (h1), poster, structured metadata fields,
    // and the IMDB ID from the embedded JavaScript player
    // ═══════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30).document

        // Title from <h1>
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Untitled"

        // Poster from <p class="showimage"> or OpenGraph
        val poster = doc.selectFirst("p.showimage img")?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // Parse structured info fields from div.description
        var year: Int? = null
        var plot: String? = null
        var quality: String? = null
        var language: String? = null
        val tags = mutableListOf<String>()

        doc.select("div.description > div").forEach { item ->
            val label = item.selectFirst("b")?.text()?.trim()?.removeSuffix(":") ?: ""
            val value = item.selectFirst("em")?.text()?.trim()
                ?: item.selectFirst("span")?.text()?.trim() ?: ""

            when {
                label.contains("Release Year", true) -> year = value.trim().toIntOrNull()
                label.contains("Description", true) -> plot = value.trim()
                label.contains("Quality", true) -> quality = value.trim()
                label.contains("Language", true) -> language = value.trim()
                label.contains("Category", true) -> {
                    item.select("a").forEach { a -> tags.add(a.text().trim()) }
                }
            }
        }

        // Fallback year from title: e.g. "Movie 2026 Hindi..."
        if (year == null) {
            year = Regex("(\\d{4})").find(title)?.groupValues?.get(1)?.toIntOrNull()
        }

        // Extract IMDB ID from the embedded player script
        // Pattern: src: "tt43670559"
        val imdbId = Regex("""src:\s*"(tt\d+)"""").find(doc.html())?.groupValues?.get(1)

        // Build LinkData to pass to loadLinks
        val linkData = LinkData(
            url = url,
            imdbId = imdbId
        ).toJson()

        // Detect series vs movie from title
        val isSeries = title.contains(Regex("(?i)(season|ep\\s*-|episode|s\\d{1,2})"))

        return if (isSeries) {
            // For series: parse related links as episodes
            val episodes = mutableListOf<Episode>()
            doc.select("div.fl.odd a.fileName, div.fl a.fileName").forEachIndexed { index, el ->
                val epUrl = el.attr("href")
                val epTitle = el.selectFirst("div > div:nth-child(2)")?.text()?.trim()
                    ?: el.attr("title")
                if (epUrl.isNotBlank()) {
                    episodes.add(
                        newEpisode(LinkData(url = epUrl, imdbId = imdbId).toJson()) {
                            this.name = epTitle
                            this.episode = index + 1
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                this.tags = tags.ifEmpty { null }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                this.tags = tags.ifEmpty { null }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // loadLinks: Extracts video stream URLs
    // Strategy 1: Use the IMDB ID with allmovieland.link player
    // Strategy 2: Find direct video/iframe links on the page
    // ═══════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = tryParseJson<LinkData>(data) ?: return false

        // Strategy 1: allmovieland.link player with IMDB ID
        if (!parsed.imdbId.isNullOrBlank()) {
            val playerUrl = "https://allmovieland.link/player?id=${parsed.imdbId}"
            try {
                val playerDoc = app.get(playerUrl, referer = mainUrl, timeout = 30).document

                // Look for iframe sources in the player page
                playerDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        loadExtractor(src, mainUrl, subtitleCallback, callback)
                    }
                }

                // Look for direct video sources
                playerDoc.select("source").forEach { source ->
                    val src = source.attr("src")
                    val quality = source.attr("label")
                    if (src.isNotBlank()) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name ${quality.ifBlank { "" }}".trim(),
                                url = src,
                                referer = playerUrl,
                                quality = getQualityFromName(quality),
                                isM3u8 = src.contains(".m3u8")
                            )
                        )
                    }
                }

                // Look for m3u8 or mp4 URLs in scripts
                val scriptContent = playerDoc.html()
                Regex("""(https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*)""").findAll(scriptContent).forEach { match ->
                    val streamUrl = match.groupValues[1]
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name Stream",
                            url = streamUrl,
                            referer = playerUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = streamUrl.contains(".m3u8")
                        )
                    )
                }
            } catch (_: Exception) { }
        }

        // Strategy 2: Re-scrape the page for any embedded iframes or direct links
        try {
            val doc = app.get(parsed.url, timeout = 30).document

            // Check for iframes
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && !src.contains("google") && !src.contains("facebook")) {
                    loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }

            // Check for direct download links (sometimes hidden behind buttons)
            doc.select("a[href*='.mp4'], a[href*='.mkv'], a[href*='download']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    loadExtractor(fixUrl(href), mainUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) { }

        return true
    }

    // ═══════════════════════════════════════════════════════════
    // Data class to pass between load() and loadLinks()
    // ═══════════════════════════════════════════════════════════
    data class LinkData(
        val url: String,
        val imdbId: String? = null,
    )
}
