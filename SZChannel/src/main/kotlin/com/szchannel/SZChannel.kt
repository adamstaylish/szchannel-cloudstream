package com.szchannel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val script = document.select("script[src]").firstOrNull { it.attr("src").substringAfterLast('/').endsWith(".js") && !it.attr("src").contains("juy.js") && !it.attr("src").contains("fg.js") }
            ?: return null
        val scriptUrl = script.absUrl("src").ifBlank { fixUrl(script.attr("src")) }
        return runCatching { app.get(scriptUrl, headers = requestHeaders).document }.getOrNull()
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = requestHeaders).document
        document.select(".servers a[href], a.ser-id[href]")
            .map { it.absUrl("href").ifBlank { it.attr("href") } }
            .filter { it.startsWith("http") }
            .distinct()
            .forEach { server ->
                runCatching { loadExtractor(server, data, subtitleCallback, callback) }
            }
        return true
    }
}
