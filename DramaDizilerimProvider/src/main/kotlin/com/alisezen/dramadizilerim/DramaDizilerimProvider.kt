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
            // Arama başarısız olursa aşağıdaki fallback kullanılacak.
        }

        /*
         * Site üzerindeki arama sonucu alınamazsa,
         * kullanıcının yazdığı isimden doğrudan dizi URL'si oluştur.
         */
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

        /*
         * SearchResponse içerisinde .data veya .url kullanmıyoruz.
         * CloudStream'in güncel API'siyle uyumlu olması için
         * sonuçları doğrudan döndürüyoruz.
         */
        return results
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        /*
         * Dizi başlığı
         */
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

        /*
         * Poster
         */
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

        /*
         * Açıklama
         */
        val description =
            doc.selectFirst(
                "meta[property='og:description']"
            )?.attr("content")

        /*
         * Bölümler
         */
        val episodes = mutableListOf<Episode>()

        /*
         * 1. yöntem:
         * option[value="/izle/..."]
         */
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

        /*
         * 2. yöntem:
         * a[href*="/izle/"]
         */
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

        /*
         * 3. yöntem:
         * data-url + data-episode kullanan yapılar
         */
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

        /*
         * Bölümleri sezon ve bölüm numarasına göre sırala.
         *
         * Burada .url veya .data kullanılmıyor.
         */
        val uniqueEpisodes =
            episodes.sortedWith(
                compareBy<Episode> { it.season }
                    .thenBy { it.episode }
            )

        /*
         * Dizi sonucu
         */
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        /*
         * Öncelikle iframe src
         */
        var embed =
            doc.selectFirst(
                "iframe[src]"
            )?.attr("abs:src")

        /*
         * iframe data-src
         */
        if (embed.isNullOrBlank()) {

            embed =
                doc.selectFirst(
                    "iframe[data-src]"
                )?.attr("data-src")
        }

        /*
         * Lazy player
         */
        if (embed.isNullOrBlank()) {

            embed =
                doc.selectFirst(
                    ".lazy-player[data-src]"
                )?.attr("data-src")
        }

        /*
         * Doğrudan video source
         */
        if (embed.isNullOrBlank()) {

            embed =
                doc.selectFirst(
                    "video source[src]"
                )?.attr("abs:src")
        }

        /*
         * Hiçbir oynatıcı bulunamadıysa
         */
        if (embed.isNullOrBlank()) {
            return false
        }

        /*
         * CloudStream extractor sistemine gönder.
         */
        return loadExtractor(
            embed,
            data,
            subtitleCallback,
            callback
        )
    }
}
