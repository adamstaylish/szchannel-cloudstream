package com.szchannel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class EgyDead : MainAPI() {
    override var mainUrl = "https://a1.egydead.media"
    override var name = "EgyDead"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9"
    )

    private enum class ContentKind { MOVIE, SERIES, UNKNOWN }

    private data class Section(
        val title: String,
        val path: String,
        val kind: ContentKind
    )

    private val sections = listOf(
        Section("الأحدث", "/", ContentKind.UNKNOWN),
        Section("أفلام أجنبية", "/category/english-movies/", ContentKind.MOVIE),
        Section("مسلسلات أجنبية", "/series-category/english-series/", ContentKind.SERIES)
    )

    private fun absoluteUrl(base: String, link: String): String {
        if (link.isBlank()) return ""
        return runCatching { URI(base).resolve(link.trim()).toString() }
            .getOrElse { fixUrl(link.trim()) }
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.lowercase() }.getOrDefault("")

    private fun pageTitle(document: Document): String {
        val meta = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return meta.ifBlank {
            document.selectFirst("h1.entry-title, .TitleMaster h1, h1")?.text()?.trim().orEmpty()
        }.ifBlank {
            document.title().substringBefore("|").trim()
        }.ifBlank {
            name
        }
    }

    private fun descriptionOf(document: Document): String? =
        document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun imageOf(element: Element, base: String): String? {
        val image = element.selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return absoluteUrl(base, raw).takeIf { it.startsWith("http") }
    }

    private fun posterOf(document: Document, pageUrl: String): String? {
        val meta = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        if (meta.isNotBlank()) return absoluteUrl(pageUrl, meta)
        return document.selectFirst(".post-thumbnail img, .PostImage img, li.movieItem img")
            ?.let { imageOf(it.parent() ?: it, pageUrl) }
    }

    private fun kindOf(url: String, title: String, forced: ContentKind): ContentKind {
        val path = pathOf(url)
        if (path.contains("/episodes/") || path.contains("/seasons/") || path.contains("/series/")) {
            return ContentKind.SERIES
        }
        if (forced != ContentKind.UNKNOWN) return forced
        val lowerTitle = title.lowercase()
        if (title.contains("فيلم") || title.contains("افلام") || title.contains("أفلام") ||
            lowerTitle.contains("movie") || lowerTitle.contains("film")
        ) {
            return ContentKind.MOVIE
        }
        if (title.contains("مسلسل") || title.contains("انمي") || title.contains("أنمي") ||
            title.contains("الحلقة") || title.contains("حلقه") || title.contains("موسم") ||
            lowerTitle.contains("series") || lowerTitle.contains("episode")
        ) {
            return ContentKind.SERIES
        }
        return ContentKind.UNKNOWN
    }

    private fun cardTitle(anchor: Element): String {
        return anchor.selectFirst(".BottomTitle")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim()
    }

    private fun cards(
        document: Document,
        pageUrl: String,
        forced: ContentKind = ContentKind.UNKNOWN
    ): List<SearchResponse> {
        return document.select("li.movieItem a[href]")
            .mapNotNull { anchor ->
                val url = anchor.absUrl("href").ifBlank {
                    absoluteUrl(pageUrl, anchor.attr("href"))
                }
                if (url.isBlank()) return@mapNotNull null

                val title = cardTitle(anchor).ifBlank { return@mapNotNull null }
                val cardText = anchor.parents()
                    .firstOrNull { it.hasClass("movieItem") }
                    ?.text()
                    .orEmpty()
                val kind = kindOf(url, "$title $cardText", forced)
                val poster = imageOf(anchor, pageUrl)
                when (kind) {
                    ContentKind.MOVIE -> newMovieSearchResponse(title, url) {
                        posterUrl = poster
                        posterHeaders = requestHeaders
                    }
                    ContentKind.SERIES -> newTvSeriesSearchResponse(title, url) {
                        posterUrl = poster
                        posterHeaders = requestHeaders
                    }
                    ContentKind.UNKNOWN -> null
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val lists = sections.mapNotNull { section ->
            runCatching {
                val url = absoluteUrl(mainUrl, section.path)
                val items = cards(
                    app.get(url, headers = requestHeaders).document,
                    url,
                    section.kind
                )
                if (items.isEmpty()) null else HomePageList(section.title, items)
            }.getOrNull()
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
        val url = "$mainUrl/?s=$encoded"
        return runCatching {
            cards(app.get(url, headers = requestHeaders).document, url)
        }.getOrDefault(emptyList())
    }

    private fun episodeNumber(label: String): Int? {
        return Regex("(?i)(?:episode\\s*|الحلقة\\s*|حلقه\\s*)(\\d+)")
            .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun episodesFromSeason(document: Document, pageUrl: String): List<Episode> {
        val anchors = document.select(
            ".seasons-list li.movieItem a[href*='/episodes/'], " +
                ".seasons-list a[href*='/episodes/'], " +
                ".episodes-list li.movieItem a[href*='/episodes/'], " +
                ".episodes-list a[href*='/episodes/']"
        )
        return anchors.mapNotNull { anchor ->
            val episodeUrl = anchor.absUrl("href").ifBlank {
                absoluteUrl(pageUrl, anchor.attr("href"))
            }
            if (episodeUrl.isBlank()) return@mapNotNull null
            val label = cardTitle(anchor).ifBlank { anchor.attr("title") }.trim()
            newEpisode(episodeUrl) {
                name = label.ifBlank { "الحلقة" }
                episode = episodeNumber(label)
                posterUrl = imageOf(anchor, pageUrl)
            }
        }.distinctBy { it.data }
    }

    private fun seasonLinks(document: Document, pageUrl: String): List<String> {
        val urls = document.select(".seasons-list a[href*='/seasons/']")
            .mapNotNull { anchor ->
                val url = anchor.absUrl("href").ifBlank {
                    absoluteUrl(pageUrl, anchor.attr("href"))
                }
                url.takeIf { it.isNotBlank() }
            }
        return urls.distinct()
    }

    private fun linkedSeriesTitle(document: Document): String? {
        return document.select("a[href*='/Series/']")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun seriesTitle(document: Document, pageUrl: String): String {
        linkedSeriesTitle(document)?.let { return it }
        if (pathOf(pageUrl).contains("/episodes/")) {
            val seasonUrl = document.select("a[href*='/seasons/']")
                .firstOrNull()
                ?.let { anchor ->
                    anchor.absUrl("href").ifBlank {
                        absoluteUrl(pageUrl, anchor.attr("href"))
                    }
                }
                .orEmpty()
            if (seasonUrl.isNotBlank()) {
                val seasonDocument = runCatching {
                    app.get(
                        seasonUrl,
                        headers = requestHeaders + ("Referer" to pageUrl)
                    ).document
                }.getOrNull()
                seasonDocument?.let { linkedSeriesTitle(it) }?.let { return it }
            }
        }
        return pageTitle(document)
    }

    private suspend fun episodesForSeries(document: Document, pageUrl: String): List<Episode> {
        val path = pathOf(pageUrl)
        if (path.contains("/seasons/")) {
            return episodesFromSeason(document, pageUrl)
        }

        if (path.contains("/episodes/")) {
            val seasonUrl = document.select("a[href*='/seasons/']")
                .firstOrNull()
                ?.let { anchor ->
                    anchor.absUrl("href").ifBlank {
                        absoluteUrl(pageUrl, anchor.attr("href"))
                    }
                }
            if (!seasonUrl.isNullOrBlank()) {
                val episodes = runCatching {
                    val seasonDocument = app.get(
                        seasonUrl,
                        headers = requestHeaders + ("Referer" to pageUrl)
                    ).document
                    episodesFromSeason(seasonDocument, seasonUrl)
                }.getOrDefault(emptyList())
                if (episodes.isNotEmpty()) return episodes
            }
            return listOf(
                newEpisode(pageUrl) {
                    name = pageTitle(document)
                    episode = episodeNumber(pageTitle(document))
                    posterUrl = posterOf(document, pageUrl)
                }
            )
        }

        val seasons = seasonLinks(document, pageUrl).take(20)
        if (seasons.isNotEmpty()) {
            val episodes = mutableListOf<Episode>()
            seasons.forEach { seasonUrl ->
                runCatching {
                    val seasonDocument = app.get(
                        seasonUrl,
                        headers = requestHeaders + ("Referer" to pageUrl)
                    ).document
                    episodes.addAll(episodesFromSeason(seasonDocument, seasonUrl))
                }
            }
            if (episodes.isNotEmpty()) return episodes.distinctBy { it.data }
        }
        return episodesFromSeason(document, pageUrl)
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absoluteUrl(mainUrl, url)
        val document = app.get(pageUrl, headers = requestHeaders).document
        val title = pageTitle(document)
        val poster = posterOf(document, pageUrl)
        val plot = descriptionOf(document)
        val path = pathOf(pageUrl)
        val isExplicitSeriesPath =
            path.contains("/episodes/") || path.contains("/seasons/") || path.contains("/series/")
        val isMovie = !isExplicitSeriesPath && (
            document.selectFirst("meta[property=og:type]")?.attr("content")
                ?.equals("video.movie", ignoreCase = true) == true ||
                title.contains("فيلم") || title.contains("أفلام")
            )

        if (isMovie) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                plot?.let { this.plot = it }
            }.also { MetadataUtils.apply(document, pageUrl, it) }
        }

        val episodes = episodesForSeries(document, pageUrl)
        return newTvSeriesLoadResponse(
            seriesTitle(document, pageUrl),
            pageUrl,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot?.let { this.plot = it }
        }.also { MetadataUtils.apply(document, pageUrl, it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourcePageUrl = absoluteUrl(mainUrl, data)
        val sourcePath = pathOf(sourcePageUrl)
        val watchUrl = if (sourcePath.trimEnd('/').endsWith("/watch")) {
            sourcePageUrl
        } else {
            val sourceDocument = runCatching {
                app.get(sourcePageUrl, headers = requestHeaders).document
            }.getOrNull() ?: return false
            sourceDocument.select("a[href*='/watch/']")
                .mapNotNull { anchor ->
                    val url = anchor.absUrl("href").ifBlank {
                        absoluteUrl(sourcePageUrl, anchor.attr("href"))
                    }
                    url.takeIf {
                        it.startsWith(mainUrl) && pathOf(it).trimEnd('/').endsWith("/watch")
                    }
                }
                .firstOrNull()
                ?: return false
        }

        val watchDocument = runCatching {
            app.get(
                watchUrl,
                headers = requestHeaders + ("Referer" to sourcePageUrl)
            ).document
        }.getOrNull() ?: return false

        val servers = watchDocument.select(
            ".serversList li[data-link], .serversList li[data-src]"
        ).mapNotNull { element ->
            val raw = element.attr("data-link").ifBlank { element.attr("data-src") }
            absoluteUrl(watchUrl, raw).takeIf { it.startsWith("http") }
        }.distinct()

        servers.forEach { server ->
            runCatching {
                loadExtractor(server, watchUrl, subtitleCallback, callback)
            }
        }
        return servers.isNotEmpty()
    }
}
