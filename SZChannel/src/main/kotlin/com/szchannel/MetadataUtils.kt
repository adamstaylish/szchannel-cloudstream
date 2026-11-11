package com.szchannel

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import org.jsoup.nodes.Document
import java.net.URI

object MetadataUtils {
    private fun absoluteUrl(base: String, raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching { URI(base).resolve(raw.trim()).toString() }.getOrElse { raw.trim() }
    }

    fun apply(document: Document, pageUrl: String, response: LoadResponse) {
        if (response.posterUrl.isNullOrBlank()) {
            val raw = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img")
                    ?.let { image ->
                        listOf("data-src", "data-lazy-src", "data-original", "src")
                            .map { image.attr(it) }
                            .firstOrNull { it.isNotBlank() }
                    }
            response.posterUrl = raw?.let { absoluteUrl(pageUrl, it) }
        }
        response.posterHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "ar,en-US;q=0.9"
        )
        if (response.plot.isNullOrBlank()) {
            response.plot = document.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        }
        val text = document.text()
        Regex("(?i)(?:السنة|year)[^0-9]{0,10}((?:19|20)\\d{2})")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { response.year = it }
        Regex("(?i)(?:التقييم|rating)[^0-9]{0,10}(\\d+(?:[.,]\\d+)?)")
            .find(text)?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.let { response.score = Score.from10(it) }
        Regex("(?i)(?:مدة الحلقة|المدة|duration)[^0-9]{0,10}(\\d+)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { response.duration = it }
        Regex("(?i)(?:الفئة العمرية|التصنيف العمري)[^:：]{0,12}[:：]?\\s*([^،,|]+)")
            .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            ?.let { response.contentRating = it }
    }
}
