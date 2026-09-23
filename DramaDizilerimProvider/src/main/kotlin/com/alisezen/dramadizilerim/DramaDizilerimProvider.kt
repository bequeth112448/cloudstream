package com.alisezen.dramadizilerim

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DramaDizilerimProvider : MainAPI() {

    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"

    private fun absoluteUrl(raw: String): String {
        val value = raw.trim()

        return when {
            value.startsWith("http://") ||
            value.startsWith("https://") -> value

            value.startsWith("/") -> mainUrl + value

            else -> "$mainUrl/$value"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val results = mutableListOf<SearchResponse>()
        val searchUrl = "$mainUrl/series"

        try {
            val doc = app.get(searchUrl).document

            doc.select("a[href*='/dizi/']").forEach { a ->

                val href = a.attr("abs:href")
                val title = a.text().trim()

                if (href.isNotBlank() && title.isNotBlank()) {

                    results += newTvSeriesSearchResponse(
                        title,
                        href,
                        TvType.TvSeries
                    )
                }
            }

        } catch (_: Exception) {
            // Fallback aşağıda
        }

        if (results.isEmpty()) {

            val slug = query.trim()
                .lowercase()
                .replace(
                    Regex("[^a-z0-9çğıöşü -]"),
                    ""
                )
                .replace(
                    Regex("\\s+"),
                    "-"
                )

            results += newTvSeriesSearchResponse(
                query.trim(),
                "$mainUrl/dizi/$slug",
                TvType.TvSeries
            )
        }

        return results.distinctBy { it.data }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        // -----------------------------
        // BAŞLIK
        // -----------------------------

        val title =
            doc.selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.substringBefore(" Türkçe Dublaj")
                ?.substringBefore(" Altyazılı")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.title()
                    .substringBefore(" | ")
                    .trim()

        // -----------------------------
        // POSTER
        // -----------------------------

        var poster: String? =
            doc.selectFirst(
                "meta[property='og:image']"
            )?.attr("content")

        if (poster.isNullOrBlank()) {

            poster = doc.select("img")
                .mapNotNull { img ->

                    val src = img.attr("abs:src")
                        .ifBlank {
                            img.attr("abs:data-src")
                        }

                    if (src.isBlank()) {
                        null
                    } else {
                        src
                    }
                }
                .firstOrNull()
        }

        // -----------------------------
        // AÇIKLAMA
        // -----------------------------

        val description =
            doc.selectFirst(
                "meta[property='og:description']"
            )?.attr("content")

        // -----------------------------
        // BÖLÜMLER
        // -----------------------------

        val episodes = mutableListOf<Episode>()

        // Site option/value yapısı
        doc.select(
            "option[value*='/izle/'], " +
            "option[value*='izle/']"
        ).forEach { option ->

            val rawUrl = option.attr("value")

            if (rawUrl.isNotBlank()) {

                val epUrl = absoluteUrl(rawUrl)

                if (epUrl.contains("/izle/")) {

                    val text = option.text().trim()

                    val match =
                        Regex(
                            "(?:Bölüm|Episode)\\s*(\\d+)",
                            RegexOption.IGNORE_CASE
                        ).find(text)

                    val episodeNumber =
                        match
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 1

                    episodes += newEpisode(epUrl) {

                        name =
                            if (text.isNotBlank()) {
                                text
                            } else {
                                "Bölüm $episodeNumber"
                            }

                        season = 1
                        episode = episodeNumber
                    }
                }
            }
        }

        // -----------------------------
        // LINK TABANLI BÖLÜMLER
        // -----------------------------

        doc.select(
            "a[href*='/izle/'], " +
            "[data-url*='/izle/']"
        ).forEach { element ->

            val rawUrl =
                element.attr("href")
                    .ifBlank {
                        element.attr("data-url")
                    }

            if (rawUrl.isNotBlank()) {

                val epUrl = absoluteUrl(rawUrl)

                if (epUrl.contains("/izle/")) {

                    val text =
                        element.attr("data-title")
                            .ifBlank {
                                element.text().trim()
                            }

                    val match =
                        Regex(
                            "(?:Bölüm|Episode)\\s*(\\d+)",
                            RegexOption.IGNORE_CASE
                        ).find(text)

                    val episodeNumber =
                        match
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: 1

                    episodes += newEpisode(epUrl) {

                        name =
                            if (text.isNotBlank()) {
                                text
                            } else {
                                "Bölüm $episodeNumber"
                            }

                        season = 1
                        episode = episodeNumber
                    }
                }
            }
        }

        // -----------------------------
        // DATA ATTRIBUTES
        // -----------------------------

        doc.select(
            "[data-url][data-episode], " +
            ".v-slide[data-url]"
        ).forEach { element ->

            val rawUrl =
                element.attr("data-url")

            if (rawUrl.isNotBlank()) {

                val epUrl = absoluteUrl(rawUrl)

                val episodeNumber =
                    element.attr("data-episode")
                        .toIntOrNull()
                        ?: 1

                val seasonNumber =
                    element.attr("data-season")
                        .toIntOrNull()
                        ?: 1

                val text =
                    element.attr("data-title")
                        .ifBlank {
                            element.text().trim()
                        }

                episodes += newEpisode(epUrl) {

                    name =
                        if (text.isNotBlank()) {
                            text
                        } else {
                            "Bölüm $episodeNumber"
                        }

                    season = seasonNumber
                    episode = episodeNumber
                }
            }
        }

        // -----------------------------
        // TEKRARLARI TEMİZLE
        // -----------------------------

        val uniqueEpisodes =
            episodes
                .distinctBy { it.url }
                .sortedWith(
                    compareBy<Episode> { it.season }
                        .thenBy { it.episode }
                )

        // -----------------------------
        // SONUÇ
        // -----------------------------

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            uniqueEpisodes
        ) {

            posterUrl = poster
            plot = description
        }
    }

    // =====================================================
    // VIDEO
    // =====================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // iframe
        var embed =
            doc.selectFirst(
                "iframe[src]"
            )?.attr("abs:src")

        // lazy iframe
        if (embed.isNullOrBlank()) {

            embed =
                doc.selectFirst(
                    "iframe[data-src]"
                )?.attr("data-src")
        }

        // lazy-player
        if (embed.isNullOrBlank()) {

            embed =
                doc.selectFirst(
                    ".lazy-player[data-src]"
                )?.attr("data-src")
        }

        // video source
        if (embed.isNullOrBlank()) {

            embed =
                doc.selectFirst(
                    "video source[src]"
                )?.attr("abs:src")
        }

        if (embed.isNullOrBlank()) {
            return false
        }

        return loadExtractor(
            embed,
            data,
            subtitleCallback,
            callback
        )
    }
}
