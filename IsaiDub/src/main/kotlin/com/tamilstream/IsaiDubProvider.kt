package com.tamilstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class IsaiDubProvider : MainAPI() {
    // ═══════════════════════════════════════════════════════════
    // Provider metadata
    // Domain verification: isaidub.me or isaidub.co redirect
    // to the current official domain
    // ═══════════════════════════════════════════════════════════
    override var mainUrl = "https://isaidub.movie"
    override var name = "IsaiDub"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ═══════════════════════════════════════════════════════════
    // Homepage categories — folder-style navigation
    // ═══════════════════════════════════════════════════════════
    override val mainPage = mainPageOf(
        "/movie/tamil-dubbed-movies-download" to "Daily Updates",
        "/tamil-2026-dubbed-movies" to "2026 Dubbed Movies",
        "/tamil-2025-dubbed-movies" to "2025 Dubbed Movies",
        "/tamil-2024-dubbed-movies" to "2024 Dubbed Movies",
        "/tamil-2023-dubbed-movies" to "2023 Dubbed Movies",
        "/tamil-2022-dubbed-movies" to "2022 Dubbed Movies",
        "/tamil-atoz-dubbed-movies" to "A-Z Dubbed Movies",
        "/tamil-dubbed-web-series" to "Web Series",
        "/tamil-chinese-dubbed-movies" to "Chinese Dubbed",
        "/movie/tamil-dubbed-movies-collections" to "Collections",
        "/movie/hollywood-movies-in-english" to "Hollywood English",
    )

    // ═══════════════════════════════════════════════════════════
    // getMainPage: Fetches paginated listings
    // IsaiDub uses ?get-page={num} for pagination
    // Movie entries are in <div class="f"> elements
    // ═══════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl${request.data}/"
        } else {
            "$mainUrl${request.data}/?get-page=$page"
        }

        val doc = app.get(url, timeout = 30).document
        val home = doc.select("div.f").mapNotNull { it.toSearchResult() }

        // Check if pagination exists
        val hasNext = doc.select("div.pagination a, .pagination a[title*='Page']").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, home),
            hasNext = hasNext
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Helper: Converts <div class="f"> list entry to SearchResponse
    //
    // IsaiDub listing format:
    // <div class="f">
    //   <strong>Title (Year) Tamil Dubbed Movie</strong>
    //   (info text)
    //   <a href="/movie/slug/">Download Now</a>
    // </div>
    // ═══════════════════════════════════════════════════════════
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href*='/movie/']") ?: return null
        val href = fixUrl(link.attr("href"))

        // Skip non-movie links (like "Check out our Latest Updates")
        if (href.contains("recent-updates")) return null

        // Title from <strong> or <b> tag
        val rawTitle = selectFirst("strong")?.text()
            ?: selectFirst("b")?.text()
            ?: link.text()
        if (rawTitle.isBlank()) return null

        // Clean title: remove "Tamil Dubbed Movie" suffix, extract name + year
        val cleanedTitle = rawTitle
            .replace(Regex("(?i)\\s*tamil\\s+dubbed\\s+(movie|full movie).*"), "")
            .replace(Regex("(?i)\\s*download\\s*$"), "")
            .trim()

        // Extract year from title like "Red Eye (2005)"
        val year = Regex("\\((\\d{4})\\)").find(cleanedTitle)?.groupValues?.get(1)?.toIntOrNull()

        // Extract rating from the entry text like "[Rating: 6.5/10]"
        val ratingText = this.text()
        val rating = Regex("Rating:\\s*(\\d+\\.?\\d*)/10").find(ratingText)?.groupValues?.get(1)

        // Detect series
        val isSeries = cleanedTitle.contains(Regex("(?i)(season|web\\s*series|episode|s\\d{1,2})"))

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanedTitle, href, TvType.TvSeries) {
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanedTitle, href, TvType.Movie) {
                this.year = year
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // search: IsaiDub has no search form. We scan multiple pages:
    // 1. Daily updates (most recent movies)
    // 2. Recent yearly pages (2026, 2025, 2024 — high hit rate)
    // 3. A-Z index as fallback (may return 500 on some letters)
    // ═══════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Strategy 1: Scan daily updates page (first few pages)
        for (page in 1..3) {
            try {
                val url = if (page == 1) "$mainUrl/movie/tamil-dubbed-movies-download/"
                    else "$mainUrl/movie/tamil-dubbed-movies-download/?get-page=$page"
                val doc = app.get(url, timeout = 30).document

                doc.select("div.f").mapNotNull { it.toSearchResult() }
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .let { results.addAll(it) }

                if (results.size >= 10) break
            } catch (_: Exception) { break }
        }

        // Strategy 2: Scan recent yearly pages (most reliable)
        if (results.size < 5) {
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            for (year in currentYear downTo (currentYear - 2)) {
                try {
                    val doc = app.get(
                        "$mainUrl/tamil-$year-dubbed-movies/",
                        timeout = 30
                    ).document

                    doc.select("div.f").mapNotNull { it.toSearchResult() }
                        .filter { it.name.contains(query, ignoreCase = true) }
                        .let { results.addAll(it) }

                    if (results.size >= 10) break
                } catch (_: Exception) { }
            }
        }

        // Strategy 3: A-Z index fallback (may 500 on some letters)
        if (results.isEmpty() && query.isNotBlank()) {
            val firstChar = query.first().lowercaseChar()
            val indexChar = if (firstChar.isLetter()) firstChar.toString() else "0"

            try {
                val doc = app.get(
                    "$mainUrl/tamil-atoz-dubbed-movies/$indexChar/",
                    timeout = 30
                ).document

                doc.select("div.f").mapNotNull { it.toSearchResult() }
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .let { results.addAll(it) }
            } catch (_: Exception) { }
        }

        return results.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════════
    // load: Fetches movie details from the movie info page
    //
    // IsaiDub movie pages have:
    // - <div id="movie-info"> with poster, metadata list
    // - <ul class="movie-info"> with labeled fields
    // - <div class="movie-synopsis"> for plot
    // - <div class="folder"> links to quality variants
    // ═══════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 30).document

        // Title from <title> tag, cleaned
        val rawTitle = doc.selectFirst("title")?.text()
            ?.replace(Regex("(?i)\\s*download\\s*isaidub.*"), "")
            ?.replace(Regex("(?i)\\s*tamil\\s+dubbed\\s+movie.*"), "")
            ?.trim()
            ?: "Untitled"

        // Poster from movie-info-container
        val poster = doc.selectFirst("div.movie-info-container img")?.attr("src")
            ?.let { fixUrl(it) }

        // Parse structured metadata from <ul class="movie-info">
        var year: Int? = null
        var plot: String? = null
        var director: String? = null
        var quality: String? = null
        var rating: String? = null
        val actors = mutableListOf<String>()
        val tags = mutableListOf<String>()

        doc.select("ul.movie-info li").forEach { li ->
            val label = li.selectFirst("strong")?.text()?.trim()?.removeSuffix(":") ?: ""
            val value = li.selectFirst("span")?.text()?.trim() ?: ""

            when {
                label.contains("Movie", true) -> {
                    // Extract year from "Red Eye (2005)"
                    year = Regex("\\((\\d{4})\\)").find(value)?.groupValues?.get(1)?.toIntOrNull()
                }
                label.contains("Director", true) -> director = value
                label.contains("Starring", true) -> {
                    actors.addAll(value.split(",").map { it.trim() }.filter { it.isNotBlank() })
                }
                label.contains("Genres", true) || label.contains("Genre", true) -> {
                    tags.addAll(value.split(",").map { it.trim() }.filter { it.isNotBlank() })
                }
                label.contains("Quality", true) -> quality = value
                label.contains("Rating", true) -> rating = value
            }
        }

        // Synopsis
        plot = doc.selectFirst("div.movie-synopsis")?.text()
            ?.replace(Regex("^Synopsis:\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim()

        // Collect quality variant links from folder navigation
        // These are <div class="folder"> with <a href="/movie/{id}/">
        val qualityLinks = doc.select("div.folder a[href*='/movie/']").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            val label = a.text().trim()
            if (href != url && label.isNotBlank()) {
                QualityLink(label = label, url = href)
            } else null
        }

        // If no quality links, this might already be a quality page with downloads
        val downloadLinks = collectDownloadLinks(doc)

        val allLinks = IsaiDubLinkData(
            qualityLinks = qualityLinks,
            downloadLinks = downloadLinks,
            pageUrl = url
        ).toJson()

        // Detect series
        val isSeries = rawTitle.contains(Regex("(?i)(season|web\\s*series|episode|s\\d{1,2})"))

        return if (isSeries) {
            newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.ifEmpty { null }
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, allLinks) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.ifEmpty { null }
                addActors(actors)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Helper: Collects download links from a page
    // Downloads are in <div class="dlink"> <a href="...">
    // ═══════════════════════════════════════════════════════════
    private fun collectDownloadLinks(doc: Document): List<DownloadLink> {
        return doc.select("div.dlink a").mapNotNull { a ->
            val href = a.attr("href")
            val label = a.text().trim()
            if (href.isNotBlank()) {
                DownloadLink(label = label, url = href)
            } else null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // loadLinks: Navigates the folder structure to find downloads
    //
    // Flow: Movie Page → Quality Folder → File List → Download Page
    //       /movie/slug/ → /movie/92510/ → /movie/92511/ → /download/page/98572/
    //
    // Final download URLs go through dubpage.xyz
    // ═══════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = tryParseJson<IsaiDubLinkData>(data) ?: return false

        // If we already have direct download links, resolve them
        if (parsed.downloadLinks.isNotEmpty()) {
            parsed.downloadLinks.forEach { dl ->
                resolveDownloadLink(dl.url, dl.label, callback)
            }
        }

        // Navigate quality links (folder structure)
        for (qualityLink in parsed.qualityLinks) {
            try {
                val qualityDoc = app.get(qualityLink.url, timeout = 30).document

                // Check for sub-folders (e.g., 720p HD, 360p HD)
                val subFolders = qualityDoc.select("div.folder a[href*='/movie/']").mapNotNull { a ->
                    val href = fixUrl(a.attr("href"))
                    val label = a.text().trim()
                    if (label.isNotBlank()) Pair(href, label) else null
                }

                if (subFolders.isNotEmpty()) {
                    // Navigate into each sub-folder to find file entries
                    for ((subUrl, subLabel) in subFolders) {
                        try {
                            val subDoc = app.get(subUrl, timeout = 30).document

                            // Look for download page links in file entries
                            // Pattern: <a href="/download/page/{id}/" class="coral">
                            subDoc.select("a[href*='/download/page/']").forEach { a ->
                                val href = fixUrl(a.attr("href"))
                                val fileLabel = a.text().trim()
                                resolveDownloadPage(href, "$subLabel - $fileLabel", callback)
                            }

                            // Also check for direct dlink entries
                            subDoc.select("div.dlink a").forEach { a ->
                                val href = a.attr("href")
                                val dlLabel = a.text().trim()
                                resolveDownloadLink(href, "$subLabel - $dlLabel", callback)
                            }
                        } catch (_: Exception) { }
                    }
                } else {
                    // No sub-folders — look for file entries directly
                    qualityDoc.select("a[href*='/download/page/']").forEach { a ->
                        val href = fixUrl(a.attr("href"))
                        val fileLabel = a.text().trim()
                        resolveDownloadPage(href, "${qualityLink.label} - $fileLabel", callback)
                    }

                    qualityDoc.select("div.dlink a").forEach { a ->
                        val href = a.attr("href")
                        val dlLabel = a.text().trim()
                        resolveDownloadLink(href, "${qualityLink.label} - $dlLabel", callback)
                    }
                }
            } catch (_: Exception) { }
        }

        // If no quality links and no downloads yet, re-scrape the page
        if (parsed.qualityLinks.isEmpty() && parsed.downloadLinks.isEmpty()) {
            try {
                val doc = app.get(parsed.pageUrl, timeout = 30).document

                // Look for any folder links to navigate
                doc.select("div.folder a[href*='/movie/']").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    val label = a.text().trim()
                    try {
                        val folderDoc = app.get(href, timeout = 30).document
                        folderDoc.select("a[href*='/download/page/']").forEach { dl ->
                            resolveDownloadPage(fixUrl(dl.attr("href")), label, callback)
                        }
                        folderDoc.select("div.dlink a").forEach { dl ->
                            resolveDownloadLink(dl.attr("href"), label, callback)
                        }
                    } catch (_: Exception) { }
                }

                // Direct download links on the page
                doc.select("a[href*='/download/page/']").forEach { a ->
                    resolveDownloadPage(fixUrl(a.attr("href")), a.text().trim(), callback)
                }
            } catch (_: Exception) { }
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════
    // Resolves a /download/page/{id}/ URL to find the actual
    // dubpage.xyz download links
    // ═══════════════════════════════════════════════════════════
    private suspend fun resolveDownloadPage(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(url, timeout = 30).document

            // Extract file info
            val fileName = doc.selectFirst("div.details:has(strong:contains(File Name))")
                ?.text()?.replace("File Name:", "")?.trim() ?: label
            val fileSize = doc.selectFirst("div.details:has(strong:contains(File Size))")
                ?.text()?.replace("File Size:", "")?.trim() ?: ""

            // Get download server links from <div class="dlink">
            doc.select("div.dlink a").forEach { a ->
                val href = a.attr("href")
                val serverLabel = a.text().trim()
                if (href.isNotBlank()) {
                    resolveDownloadLink(href, "$fileName [$fileSize] $serverLabel", callback)
                }
            }
        } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════════
    // Resolves final download links (e.g., dubpage.xyz URLs)
    // Follows redirects to get the actual video file URL
    // ═══════════════════════════════════════════════════════════
    private suspend fun resolveDownloadLink(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Try to follow the redirect to get final URL
            val response = app.get(
                url,
                allowRedirects = true,
                timeout = 30,
                referer = mainUrl
            )
            val finalUrl = response.url

            // Extract quality from label
            val quality = when {
                label.contains("1080p", true) -> Qualities.P1080.value
                label.contains("720p", true) -> Qualities.P720.value
                label.contains("480p", true) -> Qualities.P480.value
                label.contains("360p", true) -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                newExtractorLink(
                    name,
                    "$name - $label".take(100),
                    finalUrl
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                }
            )
        } catch (_: Exception) {
            // If redirect fails, try loadExtractor as fallback
            try {
                loadExtractor(url, mainUrl, { }, callback)
            } catch (_: Exception) { }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Data classes for passing data between load() and loadLinks()
    // ═══════════════════════════════════════════════════════════
    data class QualityLink(val label: String, val url: String)
    data class DownloadLink(val label: String, val url: String)
    data class IsaiDubLinkData(
        val qualityLinks: List<QualityLink> = emptyList(),
        val downloadLinks: List<DownloadLink> = emptyList(),
        val pageUrl: String = ""
    )
}
