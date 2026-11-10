package com.szchannel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Stardima : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "Stardima"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9"
    )

    private val sections = listOf(
        "أحدث الإضافات" to "/",
        "أفلام" to "/aflam",
        "مسلسلات" to "/mosalsalat"
    )

    private fun absoluteUrl(base: String, link: String): String {
        if (link.isBlank()) return ""
        return runCatching { URI(base).resolve(link.trim()).toString() }
            .getOrElse { fixUrl(link.trim()) }
    }

    private fun pageTitle(document: Document): String {
        val openGraphTitle = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        return openGraphTitle.ifBlank {
            document.selectFirst("h1")?.text()?.trim().orEmpty()
        }.ifBlank {
            document.title().substringBefore(" - ").trim()
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

    private fun titleOf(anchor: Element): String {
        val posterAlt = anchor.selectFirst("img[alt]")?.attr("alt").orEmpty()
            .removePrefix("Poster for ")
            .trim()
        return posterAlt.ifBlank {
            anchor.selectFirst("h2, h3, h4, .font-bold")?.text()?.trim().orEmpty()
        }.ifBlank {
            anchor.text().trim().substringBefore(" • ").substringBefore(" مسلسل ")
                .substringBefore(" فيلم ")
        }
    }

    private fun cards(document: Document, pageUrl: String): List<SearchResponse> {
        return document.select("a[href*='/movie/'], a[href*='/tvshow/']")
            .mapNotNull { anchor ->
                val url = anchor.absUrl("href").ifBlank {
                    absoluteUrl(pageUrl, anchor.attr("href"))
                }
                if (url.isBlank() || url.contains("/play/")) return@mapNotNull null

                val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
                val title = titleOf(anchor).ifBlank { return@mapNotNull null }
                val poster = imageOf(anchor, pageUrl)
                when {
                    path.contains("/movie/") -> newMovieSearchResponse(title, url) {
                        posterUrl = poster
                        posterHeaders = requestHeaders
                    }
                    path.contains("/tvshow/") -> newTvSeriesSearchResponse(title, url) {
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
        val url = "$mainUrl/search?query=$encoded"
        return runCatching {
            cards(app.get(url, headers = requestHeaders).document, url)
        }.getOrDefault(emptyList())
    }

    private fun episodeLinks(document: Document, pageUrl: String): List<Pair<String, String>> {
        val byUrl = linkedMapOf<String, String>()
        document.select("a[href*='/play/']").forEach { anchor ->
            val url = anchor.absUrl("href").ifBlank {
                absoluteUrl(pageUrl, anchor.attr("href"))
            }
            if (url.isBlank() || !url.contains("/play/")) return@forEach
            byUrl.putIfAbsent(url, anchor.text().trim())
        }

        val ogVideo = document.selectFirst("meta[property=og:video]")?.attr("content").orEmpty()
        if (ogVideo.isNotBlank()) {
            val url = absoluteUrl(pageUrl, ogVideo)
            val current = absoluteUrl(pageUrl, pageUrl)
            if (url.contains("/play/") && url != current) byUrl.putIfAbsent(url, "")
        }
        return byUrl.map { it.key to it.value }
    }

    private fun descriptionOf(document: Document): String? =
        document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun posterOf(document: Document, pageUrl: String): String? {
        val fromMeta = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        if (fromMeta.isNotBlank()) return absoluteUrl(pageUrl, fromMeta)
        return document.selectFirst("img[alt^=Poster]")?.let { imageOf(it.parent() ?: it, pageUrl) }
    }

    private suspend fun episodeName(url: String, fallback: String, referer: String): String {
        if (fallback.isNotBlank() && !fallback.equals("تشغيل", ignoreCase = true)) return fallback
        return runCatching {
            pageTitle(app.get(url, headers = requestHeaders + ("Referer" to referer)).document)
        }.getOrDefault(fallback.ifBlank { "مشاهدة" })
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = absoluteUrl(mainUrl, url)
        val document = app.get(pageUrl, headers = requestHeaders).document
        val title = pageTitle(document)
        val poster = posterOf(document, pageUrl)
        val plot = descriptionOf(document)
        val path = runCatching { URI(pageUrl).path.lowercase() }.getOrDefault("")

        if (path.contains("/movie/")) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                posterUrl = poster
                plot?.let { this.plot = it }
            }
        }

        val episodes = episodeLinks(document, pageUrl).mapIndexed { index, (episodeUrl, label) ->
            val episodeName = episodeName(episodeUrl, label, pageUrl)
            val episodeNumber = Regex("(?i)(?:s\\d+e|episode\\s*|الحلقة\\s*)(\\d+)")
                .find(episodeName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(episodeUrl) {
                name = episodeName.ifBlank { "الحلقة ${index + 1}" }
                episode = episodeNumber
                posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot?.let { this.plot = it }
        }
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

        val links = linkedSetOf<String>()
        document.select("iframe[src], iframe[data-src], video source[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }
            val resolved = absoluteUrl(pageUrl, raw)
            if (resolved.startsWith("http") && resolved != pageUrl) links.add(resolved)
        }
        document.select("a[href*='/play/']").forEach { anchor ->
            val resolved = anchor.absUrl("href").ifBlank {
                absoluteUrl(pageUrl, anchor.attr("href"))
            }
            if (resolved.startsWith("http") && resolved != pageUrl) links.add(resolved)
        }
        val declaredVideo = document.selectFirst("meta[property=og:video]")
            ?.attr("content")
            .orEmpty()
        val declaredVideoUrl = absoluteUrl(pageUrl, declaredVideo)
        if (declaredVideoUrl.startsWith("http") && declaredVideoUrl != pageUrl) {
            links.add(declaredVideoUrl)
        }

        links.forEach { link ->
            runCatching {
                loadExtractor(link, pageUrl, subtitleCallback, callback)
            }
        }
        return links.isNotEmpty()
    }
}
