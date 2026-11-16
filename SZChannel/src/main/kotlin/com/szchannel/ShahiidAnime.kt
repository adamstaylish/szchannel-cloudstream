package com.szchannel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class ShahiidAnime : MainAPI() {
    override var mainUrl = "https://shahiid-anime.net"
    override var name = "Shahiid Anime"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9"
    )

    private fun absoluteUrl(base: String, raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching { URI(base).resolve(raw.trim()).toString() }
            .getOrElse { fixUrl(raw.trim()) }
    }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.lowercase() }.getOrDefault("")

    private fun pageTitle(document: Document): String {
        val meta = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return meta.ifBlank {
            document.selectFirst("h1.entry-title, h1.post-title, .anime-title, h1")?.text()?.trim().orEmpty()
        }.ifBlank {
            document.title().substringBefore("–").substringBefore("-").trim()
        }.ifBlank { name }
            .substringBefore(" – Shahiid Anime")
            .substringBefore(" - Shahiid Anime")
            .trim()
    }

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
        return document.selectFirst("img.wp-post-image, .post-thumbnail img, .one-poster img, img")
            ?.let { imageOf(it.parent() ?: it, pageUrl) }
    }

    private fun cardTitle(anchor: Element): String =
        anchor.selectFirst("h2, h3, h4, .title, .poster-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.parent()?.selectFirst("h2, h3, h4, .title, .poster-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("img[alt]")?.attr("alt")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim()

    private fun isContentUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        if (host != "shahiid-anime.net" && host != "www.shahiid-anime.net") return false
        val path = uri.path.lowercase()
        return listOf(
            "/anime/", "/episodes/", "/episodesdubbed/",
            "/series/", "/seriesdubbed/", "/seasons/", "/seasonsdubbed/"
        ).any { path.startsWith(it) }
    }

    private fun isListingUrl(url: String): Boolean =
        pathOf(url).trimEnd('/') in setOf(
            "/anime", "/episodes", "/episodesdubbed",
            "/series", "/seriesdubbed", "/seasons", "/seasonsdubbed"
        )

    private fun isEpisodeUrl(url: String): Boolean {
        val path = pathOf(url)
        return path.startsWith("/episodes/") || path.startsWith("/episodesdubbed/")
    }

    private fun isSeasonUrl(url: String): Boolean {
        val path = pathOf(url)
        return path.startsWith("/seasons/") || path.startsWith("/seasonsdubbed/")
    }

    private fun cards(document: Document, pageUrl: String): List<SearchResponse> {
        val posterCards = document.select(".one-poster")
        val candidates = if (posterCards.isNotEmpty()) posterCards.toList() else document.select("a[href]").toList()
        return candidates.mapNotNull { card ->
            val anchor = card.select("a[href]").firstOrNull { candidate ->
                val candidateUrl = candidate.absUrl("href")
                    .ifBlank { absoluteUrl(pageUrl, candidate.attr("href")) }
                isContentUrl(candidateUrl) && !isListingUrl(candidateUrl)
            } ?: return@mapNotNull null
            val url = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            if (url.isBlank()) return@mapNotNull null
            val title = cardTitle(card).ifBlank { cardTitle(anchor) }
                .ifBlank { return@mapNotNull null }
            val poster = imageOf(card, pageUrl) ?: imageOf(anchor, pageUrl)
            val path = pathOf(url)
            if (path.startsWith("/anime/")) {
                newMovieSearchResponse(title, url) {
                    posterUrl = poster
                    posterHeaders = requestHeaders
                }
            } else {
                newTvSeriesSearchResponse(title, url) {
                    posterUrl = poster
                    posterHeaders = requestHeaders
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val sections = listOf(
            "آخر الحلقات" to "/episodes/",
            "أفلام الأنمي" to "/anime/",
            "قائمة الأنمي" to "/series/",
            "الأنميات المدبلجة" to "/seriesDubbed/"
        )
        val lists = sections.mapNotNull { (title, path) ->
            runCatching {
                val url = absoluteUrl(mainUrl, path)
                val items = cards(app.get(url, headers = requestHeaders).document, url)
                if (items.isEmpty()) null else HomePageList(title, items)
            }.getOrNull()
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(normalized, "UTF-8").replace("+", "%20")
        val url = "$mainUrl/?s=$encoded"
        return runCatching { cards(app.get(url, headers = requestHeaders).document, url) }
            .getOrDefault(emptyList())
    }

    private fun episodeNumber(text: String): Int? =
        Regex("(?i)(?:الحلقة|حلقة|episode|ep)[^0-9]{0,8}(\\d+)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:-|/)(\\d+)(?:/|$)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun seasonNumber(text: String): Int? =
        Regex("(?i)(?:الموسم|season|s)[^0-9]{0,8}(\\d+)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun episodeLinks(document: Document, pageUrl: String): List<Episode> =
        document.select("a[href]").mapNotNull { anchor ->
            val url = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            if (url.isBlank() || !isContentUrl(url) || !isEpisodeUrl(url)) return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            newEpisode(url) {
                name = label.ifBlank { "الحلقة" }
                episode = episodeNumber(label + " " + url)
                season = seasonNumber(label + " " + url)
                posterUrl = imageOf(anchor, pageUrl)
            }
        }.distinctBy { it.data }

    private fun seasonLinks(document: Document, pageUrl: String): List<String> =
        document.select("a[href]").mapNotNull { anchor ->
            val url = anchor.absUrl("href").ifBlank { absoluteUrl(pageUrl, anchor.attr("href")) }
            url.takeIf { it.isNotBlank() && isContentUrl(it) && isSeasonUrl(it) }
        }.distinct()

    private fun firstNumber(document: Document, pattern: Regex): Int? =
        pattern.find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun applyMetadata(document: Document, pageUrl: String, response: LoadResponse) {
        response.posterUrl = posterOf(document, pageUrl)
        response.posterHeaders = requestHeaders
        document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }?.let { response.plot = it }
        firstNumber(document, Regex("(?i)(?:السنة|year)[^0-9]{0,10}((?:19|20)\\d{2})"))
            ?.let { response.year = it }
        Regex("(?i)(?:التقييم|rating)[^0-9]{0,10}(\\d+(?:[.,]\\d+)?)")
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.let { response.score = Score.from10(it) }
        Regex("(?i)(?:مدة الحلقة|المدة|duration)[^0-9]{0,10}(\\d+)")
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { response.duration = it }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absoluteUrl(mainUrl, url)
        val document = app.get(pageUrl, headers = requestHeaders).document
        val title = pageTitle(document)
        val path = pathOf(pageUrl)
        if (path.startsWith("/anime/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl).also {
                applyMetadata(document, pageUrl, it)
            }
        }

        val episodes = mutableListOf<Episode>()
        episodes.addAll(episodeLinks(document, pageUrl))
        if (episodes.isEmpty()) {
            seasonLinks(document, pageUrl).take(20).forEach { seasonUrl ->
                runCatching {
                    episodes.addAll(episodeLinks(app.get(seasonUrl, headers = requestHeaders + ("Referer" to pageUrl)).document, seasonUrl))
                }
            }
        }
        if (episodes.isEmpty() && isEpisodeUrl(pageUrl)) {
            episodes.add(newEpisode(pageUrl) {
                name = title
                episode = episodeNumber(title)
                posterUrl = posterOf(document, pageUrl)
            })
        }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes.distinctBy { it.data })
            .also { applyMetadata(document, pageUrl, it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(mainUrl, data)
        val initial = runCatching { app.get(pageUrl, headers = requestHeaders) }.getOrNull()
            ?: return false
        val document = initial.document
        val iframeLinks = document.select("iframe[src], iframe[data-src]").mapNotNull { element ->
            val raw = element.attr("data-src").ifBlank { element.attr("src") }
            absoluteUrl(pageUrl, raw).takeIf { it.startsWith("http") }
        }.toMutableList()
        val mediaLinks = document.select("video source[src], video source[data-src]").mapNotNull { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            absoluteUrl(pageUrl, raw).takeIf { it.startsWith("http") }
        }

        // Share4Max's iframe page is an Inertia page; its public streams prop
        // lists the mirrors. Read the version and request that prop from the
        // iframe URL, not from the parent Shahiid episode page.
        val candidates = mutableListOf<Pair<String, String>>()
        mediaLinks.forEach { candidates.add(it to pageUrl) }
        iframeLinks.forEach { playerUrl ->
            candidates.add(playerUrl to pageUrl)
            val playerPage = runCatching {
                app.get(playerUrl, headers = requestHeaders + ("Referer" to pageUrl))
            }.getOrNull() ?: return@forEach
            val version = Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(playerPage.text)?.groupValues?.getOrNull(1)
                ?: return@forEach
            val streamsResponse = runCatching {
                app.get(
                    playerUrl,
                    headers = requestHeaders + mapOf(
                        "Referer" to playerUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-Inertia" to "true",
                        "X-Inertia-Version" to version,
                        "X-Inertia-Partial-Component" to "files/mirror/video",
                        "X-Inertia-Partial-Data" to "streams",
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text
            }.getOrNull() ?: return@forEach

            runCatching {
                val root = JSONObject(streamsResponse)
                val data = root.optJSONObject("props")
                    ?.optJSONObject("streams")
                    ?.optJSONArray("data") ?: return@runCatching
                for (index in 0 until data.length()) {
                    val mirrors = data.optJSONObject(index)?.optJSONArray("mirrors") ?: continue
                    for (mirrorIndex in 0 until mirrors.length()) {
                        val raw = mirrors.optJSONObject(mirrorIndex)?.optString("link").orEmpty()
                        if (raw.isNotBlank()) {
                            candidates.add(absoluteUrl(playerUrl, raw) to playerUrl)
                        }
                    }
                }
            }
        }

        var loaded = false
        candidates.distinctBy { it.first }.forEach { (link, referer) ->
            runCatching {
                if (loadExtractor(link, referer, subtitleCallback, callback)) loaded = true
            }
        }
        return loaded
    }
}
