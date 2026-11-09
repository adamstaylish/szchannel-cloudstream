package com.szchannel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element


class SZChannel : MainAPI() {
    override var mainUrl = "https://g.szchannel.org"
    override var name = "SZChannel"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    private val categories = listOf(
        "أحدث الحلقات" to "/",
        "مسلسلات 2M" to "/mosalsalat-2m",
        "مسلسلات تركية مترجمة" to "/mosalsalat-turkia-motarjama",
        "مسلسلات عربية" to "/mosalsalat-arabia",
        "مسلسلات مدبلجة" to "/mosalsalat-modablaja"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9"
    )

    private fun imageOf(element: Element): String? {
        val image = element.selectFirst("img") ?: return null
        return image.attr("data-src").ifBlank {
            image.attr("data-lazy-src")
        }.ifBlank {
            image.attr("src")
        }.takeIf { it.isNotBlank() }
    }

    private fun listing(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".listed").mapNotNull { item ->
            val anchor = item.selectFirst("a.title") ?: item.selectFirst("a[href]") ?: return@mapNotNull null
            val url = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }
            if (url.isBlank()) return@mapNotNull null
            val title = anchor.text().trim().ifBlank { return@mapNotNull null }
            newTvSeriesSearchResponse(title, url) {
                posterUrl = imageOf(item)
                posterHeaders = requestHeaders
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 0) return newHomePageResponse(emptyList())
        val pages = categories.mapNotNull { (title, path) ->
            val response = runCatching { app.get(fixUrl(path), headers = requestHeaders) }.getOrNull() ?: return@mapNotNull null
            val items = listing(response.document)
            if (items.isEmpty()) null else HomePageList(title, items)
        }
        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return categories.drop(1).flatMap { (_, path) ->
            runCatching {
                listing(app.get(fixUrl(path), headers = requestHeaders).document)
                    .filter { it.name.lowercase().contains(normalized) }
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
    }

    private suspend fun episodeScript(document: org.jsoup.nodes.Document): org.jsoup.nodes.Document? {
    val script = document.select("script[src]").firstOrNull {
        it.attr("src").substringAfterLast('/').endsWith(".js") &&
            !it.attr("src").contains("juy.js") &&
            !it.attr("src").contains("fg.js")
    } ?: return null

    val scriptUrl = script.absUrl("src").ifBlank {
        fixUrl(script.attr("src"))
    }

    return runCatching {
        val source = app.get(scriptUrl, headers = requestHeaders).text
        val html = source
            .substringAfter("document.write(`", source)
            .substringBeforeLast("`)", source)

        Jsoup.parse(html, scriptUrl)
    }.getOrNull()
}


    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = requestHeaders)
        val document = response.document
        val title = document.selectFirst("h1.tx")?.text()?.trim()
            ?: document.title()?.substringBefore("-")?.trim()
            ?: "SZChannel"
        val episodeDocument = episodeScript(document)
        val source = episodeDocument ?: document
        val poster = source.selectFirst("img.imglisted")?.attr("src")
            ?: source.selectFirst("img")?.attr("src")
        val episodes = source.select(".listed").mapNotNull { item ->
            val anchor = item.selectFirst("a.title") ?: item.selectFirst("a[href]") ?: return@mapNotNull null
            val epUrl = anchor.absUrl("href").ifBlank { fixUrl(anchor.attr("href")) }
            if (epUrl.isBlank()) return@mapNotNull null
            val numberText = item.selectFirst(".spn-title")?.text()?.trim() ?: anchor.text()
            val number = Regex("\\d+").find(numberText)?.value?.toIntOrNull()
            newEpisode(epUrl) {
                name = anchor.text().trim().ifBlank { "الحلقة ${number ?: ""}" }
                episode = number
                posterUrl = poster
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
        }
    }

    /** Resolves a possibly relative link against a base URL. */
    private fun resolveUrl(base: String, link: String): String {
        if (link.isBlank()) return ""
        return runCatching { java.net.URI(base).resolve(link.trim()).toString() }
            .getOrElse { fixUrl(link.trim()) }
    }

    /** Collects every playable server link from a document. */
    private fun serverLinks(document: org.jsoup.nodes.Document, base: String): List<String> {
        return document.select(".servers a[href], a.ser-id[href]")
            .map { element ->
                val raw = element.attr("href")
                val absolute = element.absUrl("href").ifBlank { resolveUrl(base, raw) }
                absolute
            }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Open the episode page.
        val episodeDocument = runCatching { app.get(data, headers = requestHeaders).document }.getOrNull()

        // 2. Extract iframe#iframe[src] (or the first iframe carrying a src).
        val iframeUrl = episodeDocument?.let { document ->
            val iframe = document.selectFirst("iframe#iframe[src]")
                ?: document.selectFirst("iframe[src]")
            iframe?.let { element ->
                val raw = element.attr("src")
                element.absUrl("src").ifBlank { resolveUrl(data, raw) }
            }
        }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        val servers = LinkedHashSet<String>()

        // 3-5. Open the iframe page and pull the server links from it.
        if (iframeUrl != null) {
            val iframeDocument = runCatching { app.get(iframeUrl, headers = requestHeaders).document }.getOrNull()
            if (iframeDocument != null) {
                servers.addAll(serverLinks(iframeDocument, iframeUrl))
            }
        }

        // 7. Fallback to the original episode page when the iframe fails or has no servers.
        if (servers.isEmpty() && episodeDocument != null) {
            servers.addAll(serverLinks(episodeDocument, data))
        }

        // 6 & 8. Pass each unique link to loadExtractor with the episode page as referer,
        // isolating failures so one broken server cannot stop the rest.
        servers.forEach { server ->
            runCatching { loadExtractor(server, data, subtitleCallback, callback) }
        }

        return servers.isNotEmpty()
    }
}
