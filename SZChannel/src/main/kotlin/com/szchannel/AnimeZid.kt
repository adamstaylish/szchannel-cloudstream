package com.szchannel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class AnimeZid : MainAPI() {
    // animezid.cam currently redirects to this canonical host.
    override var mainUrl = "https://animezid.org"
    override var name = "AnimeZid"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9"
    )

    private val sections = listOf(
        "أفلام" to "/category.php?cat=movies",
        "مسلسلات" to "/category.php?cat=series"
    )

    private fun absoluteUrl(base: String, link: String): String {
        if (link.isBlank()) return ""
        return runCatching { URI(base).resolve(link.trim()).toString() }
            .getOrElse { fixUrl(link.trim()) }
    }

    private fun pageTitle(document: Document): String {
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        return title.ifBlank {
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        }.ifBlank {
            document.title().trim()
        }.ifBlank {
            name
        }
    }

    private fun imageOf(element: Element, base: String): String? {
        val image = element.selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .map { image.attr(it).trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return absoluteUrl(base, raw).takeIf { it.startsWith("http") }
    }

    private fun cardTitle(anchor: Element): String {
        return anchor.selectFirst(".az-card__title, h2, h3, h4")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("aria-label").removePrefix("مشاهدة ").trim()
                .ifBlank { anchor.text().trim() }
    }

    private fun cards(
        document: Document,
        pageUrl: String,
        expectedMovie: Boolean? = null
    ): List<SearchResponse> {
        return document.select("a.az-card__link[href], a.az-showcase-card__link[href]")
            .mapNotNull { anchor ->
                val url = anchor.absUrl("href").ifBlank {
                    absoluteUrl(pageUrl, anchor.attr("href"))
                }
                if (url.isBlank()) return@mapNotNull null

                val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
                val title = cardTitle(anchor).ifBlank { return@mapNotNull null }
                val poster = imageOf(anchor, pageUrl)
                val cardText = anchor.parents()
                    .firstOrNull { it.hasClass("az-card") }
                    ?.text()
                    .orEmpty()
                val classificationText = "$title $cardText"
                val isSeries = path.startsWith("/series/") ||
                    anchor.parents().any { it.hasClass("az-card--series") }
                val isMovie = expectedMovie == true ||
                    (!isSeries && (classificationText.contains("فيلم", ignoreCase = true) ||
                        classificationText.contains("movie", ignoreCase = true) ||
                        anchor.parents().any { it.hasClass("az-card--movie") }))

                when {
                    isSeries -> newTvSeriesSearchResponse(title, url) {
                        posterUrl = poster
                        posterHeaders = requestHeaders
                    }
                    isMovie -> newMovieSearchResponse(title, url) {
                        posterUrl = poster
                        posterHeaders = requestHeaders
                    }
                    path.endsWith("watch.php") -> newTvSeriesSearchResponse(title, url) {
                        posterUrl = poster
                        posterHeaders = requestHeaders
                    }
                    else -> null
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val lists = sections.mapNotNull { (title, path) ->
            runCatching {
                val url = absoluteUrl(mainUrl, path)
                val expectedMovie = path.contains("cat=movies")
                val items = cards(
                    app.get(url, headers = requestHeaders).document,
                    url,
                    expectedMovie
                )
                if (items.isEmpty()) null else HomePageList(title, items)
            }.getOrNull()
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
        val url = "$mainUrl/search.php?keywords=$encoded"
        return runCatching {
            cards(app.get(url, headers = requestHeaders).document, url)
        }.getOrDefault(emptyList())
    }

    private fun movieType(document: Document, url: String): Boolean {
        val openGraphType = document.selectFirst("meta[property=og:type]")
            ?.attr("content")
            ?.lowercase()
            .orEmpty()
        if (openGraphType == "video.movie") return true
        if (openGraphType == "video.episode") return false
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return path.endsWith("watch.php") && pageTitle(document).contains("فيلم")
    }

    private fun descriptionOf(document: Document): String? =
        document.selectFirst("meta[name=description]")?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun posterOf(document: Document, pageUrl: String): String? {
        val meta = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        if (meta.isNotBlank()) return absoluteUrl(pageUrl, meta)
        return document.selectFirst("main#main-content img")?.let { imageOf(it.parent() ?: it, pageUrl) }
    }

    private fun episodeNumber(label: String): Int? {
        return Regex("(?i)(?:episode\\s*|الحلقة\\s*|حلقة\\s*)(\\d+)")
            .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun episodesFrom(document: Document, pageUrl: String): List<Episode> {
        val links = document.select(
            ".az-cinema-episode-grid a[href*='watch.php?vid'], " +
                ".az-series-episode-grid a[href*='watch.php?vid'], " +
                "a.az-card__link[href*='watch.php?vid']"
        )
        return links.mapNotNull { anchor ->
            val url = anchor.absUrl("href").ifBlank {
                absoluteUrl(pageUrl, anchor.attr("href"))
            }
            if (url.isBlank()) return@mapNotNull null
            val label = anchor.attr("aria-label").ifBlank {
                anchor.attr("title").ifBlank { anchor.text() }
            }.trim()
            newEpisode(url) {
                name = label.ifBlank { "الحلقة" }
                episode = episodeNumber(label)
            }
        }.distinctBy { it.data }
    }

    private fun seriesTitle(document: Document): String {
        val breadcrumbTitle = document.select("a[href*='/series/']")
            .firstOrNull { !it.attr("href").contains("/season/") }
            ?.text()
            ?.trim()
            .orEmpty()
        return breadcrumbTitle.ifBlank { pageTitle(document) }
    }

    private suspend fun episodesForSeries(document: Document, pageUrl: String): List<Episode> {
        val path = runCatching { URI(pageUrl).path.lowercase() }.getOrDefault("")
        val seasonUrls = linkedSetOf<String>()

        if (path.contains("/season/")) {
            seasonUrls.add(pageUrl)
        } else if (path.startsWith("/series/")) {
            document.select("a[href*='/season/']").forEach { anchor ->
                val url = anchor.absUrl("href").ifBlank {
                    absoluteUrl(pageUrl, anchor.attr("href"))
                }
                if (url.isNotBlank()) seasonUrls.add(url)
            }
        } else {
            val season = document.select("a[href*='/season/']")
                .firstOrNull { it.attr("href").contains("/season/") }
            season?.let {
                val url = it.absUrl("href").ifBlank {
                    absoluteUrl(pageUrl, it.attr("href"))
                }
                if (url.isNotBlank()) seasonUrls.add(url)
            }
        }

        val episodes = mutableListOf<Episode>()
        seasonUrls.take(20).forEach { seasonUrl ->
            runCatching {
                val seasonDocument = app.get(
                    seasonUrl,
                    headers = requestHeaders + ("Referer" to pageUrl)
                ).document
                episodes.addAll(episodesFrom(seasonDocument, seasonUrl))
            }
        }
        if (episodes.isEmpty()) episodes.addAll(episodesFrom(document, pageUrl))
        return episodes.distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absoluteUrl(mainUrl, url)
        val document = app.get(pageUrl, headers = requestHeaders).document
        val title = pageTitle(document)
        val poster = posterOf(document, pageUrl)
        val plot = descriptionOf(document)

        if (movieType(document, pageUrl)) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                plot?.let { this.plot = it }
            }.also { MetadataUtils.apply(document, pageUrl, it) }
        }

        val episodes = episodesForSeries(document, pageUrl)
        return newTvSeriesLoadResponse(
            seriesTitle(document),
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
        val pageUrl = absoluteUrl(mainUrl, data)
        val document = runCatching {
            app.get(pageUrl, headers = requestHeaders).document
        }.getOrNull() ?: return false

        // AnimeZid's current watch pages expose episode metadata but no player
        // iframe, video source, or subtitle track in the returned HTML.
        val links = document.select(
            "main#main-content iframe[src], main#main-content iframe[data-src], " +
                "main#main-content video source[src]"
        ).mapNotNull { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            val url = absoluteUrl(pageUrl, raw)
            url.takeIf {
                it.startsWith("http") &&
                    !it.contains("youtube.com/embed", ignoreCase = true) &&
                    !it.contains("youtu.be/", ignoreCase = true)
            }
        }.distinct()

        links.forEach { link ->
            runCatching {
                loadExtractor(link, pageUrl, subtitleCallback, callback)
            }
        }
        return links.isNotEmpty()
    }
}
