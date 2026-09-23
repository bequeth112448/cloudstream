package com.alisezen.dramadizilerim

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DramaDizilerimProvider : MainAPI() {

    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"

    override val mainPage = mainPageOf(
        "$mainUrl/dizi" to "Diziler"
    )

    private fun absoluteUrl(raw: String): String {
        val value = raw.trim()

        if (value.isBlank()) {
            return ""
        }

        return when {
            value.startsWith("http://") ||
            value.startsWith("https://") -> value

            value.startsWith("//") -> "https:$value"

            value.startsWith("/") -> "$mainUrl$value"

            else -> "$mainUrl/$value"
        }
    }

    private fun posterFromElement(element: org.jsoup.nodes.Element): String? {
        val poster =
            element.attr("data-poster")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }

        return if (poster.isBlank()) {
            null
        } else {
            absoluteUrl(poster)
        }
    }

    private fun titleFromElement(
        element: org.jsoup.nodes.Element
    ): String {

        val dataTitle =
            element.attr("data-title").trim()

        if (dataTitle.isNotBlank()) {
            return dataTitle
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        val title =
            element.selectFirst(
                "img[alt]"
            )?.attr("alt")?.trim()

        if (!title.isNullOrBlank()) {
            return title
        }

        return element.text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document =
            app.get(request.data).document

        val results =
            mutableListOf<SearchResponse>()

        val seen =
            HashSet<String>()

        document.select(
            "a[href*='/dizi/']"
        ).forEach { element ->

            val url =
                element.attr("abs:href")
                    .trim()

            if (
                url.isBlank() ||
                !url.contains("/dizi/")
            ) {
                return@forEach
            }

            val normalizedUrl =
                url.substringBefore("#")
                    .substringBefore("?")

            if (!seen.add(normalizedUrl)) {
                return@forEach
            }

            val title =
                titleFromElement(element)

            if (title.isBlank()) {
                return@forEach
            }

            val poster =
                posterFromElement(element)
                    ?: element
                        .selectFirst("img")
                        ?.let { img ->
                            posterFromElement(img)
                        }

            results +=
                newTvSeriesSearchResponse(
                    title,
                    normalizedUrl,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
        }

        return newHomePageResponse(
            request.name,
            results
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val results =
            mutableListOf<SearchResponse>()

        val seen =
            HashSet<String>()

        try {

            val document =
                app.get("$mainUrl/series").document

            document.select(
                "a[href*='/dizi/']"
            ).forEach { element ->

                val url =
                    element.attr("abs:href")
                        .trim()

                if (
                    url.isBlank() ||
                    !url.contains("/dizi/")
                ) {
                    return@forEach
                }

                val normalizedUrl =
                    url.substringBefore("#")
                        .substringBefore("?")

                if (!seen.add(normalizedUrl)) {
                    return@forEach
                }

                val title =
                    titleFromElement(element)

                if (title.isBlank()) {
                    return@forEach
                }

                if (
                    query.isNotBlank() &&
                    !title.contains(
                        query,
                        ignoreCase = true
                    )
                ) {
                    return@forEach
                }

                val poster =
                    posterFromElement(element)
                        ?: element
                            .selectFirst("img")
                            ?.let { img ->
                                posterFromElement(img)
                            }

                results +=
                    newTvSeriesSearchResponse(
                        title,
                        normalizedUrl,
                        TvType.TvSeries
                    ) {
                        posterUrl = poster
                    }
            }

        } catch (_: Exception) {
            // Fallback aşağıda.
        }

        /*
         * Site arama sayfası değişirse,
         * doğrudan dizi slug'ı üzerinden açmayı deniyoruz.
         */
        if (results.isEmpty() && query.isNotBlank()) {

            val slug =
                query.trim()
                    .lowercase()
                    .replace(
                        Regex("[^a-z0-9çğıöşü -]"),
                        ""
                    )
                    .replace(
                        Regex("\\s+"),
                        "-"
                    )

            results +=
                newTvSeriesSearchResponse(
                    query.trim(),
                    "$mainUrl/dizi/$slug",
                    TvType.TvSeries
                )
        }

        return results
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(url).document

        val title =
            document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.substringBefore(" Türkçe Dublaj")
                ?.substringBefore(" Altyazılı")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .title()
                    .substringBefore(" | ")
                    .trim()

        var poster =
            document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()

        /*
         * Dizi sayfasındaki gerçek poster.
         */
        if (poster.isNullOrBlank()) {

            poster =
                document
                    .selectFirst(
                        "[data-poster]"
                    )
                    ?.attr("data-poster")
                    ?.trim()
        }

        if (poster.isNullOrBlank()) {

            poster =
                document
                    .select("img")
                    .mapNotNull { image ->

                        val src =
                            image
                                .attr("abs:src")
                                .ifBlank {
                                    image.attr(
                                        "abs:data-src"
                                    )
                                }

                        src.takeIf {
                            it.isNotBlank()
                        }
                    }
                    .firstOrNull()
        }

        if (!poster.isNullOrBlank()) {
            poster = absoluteUrl(poster!!)
        }

        val description =
            document
                .selectFirst(
                    "meta[property='og:description']"
                )
                ?.attr("content")
                ?.trim()

        val episodes =
            mutableListOf<Episode>()

        /*
         * Sitenin gerçek bölüm yapısı:
         *
         * <section
         *   class="v-slide"
         *   data-url=".../izle/..."
         *   data-episode="1"
         *   data-season="1"
         *   data-poster="..."
         * >
         */
        document.select(
            ".v-slide[data-url]"
        ).forEach { slide ->

            val rawUrl =
                slide.attr("data-url")
                    .trim()

            if (rawUrl.isBlank()) {
                return@forEach
            }

            val episodeUrl =
                absoluteUrl(rawUrl)

            if (!episodeUrl.contains("/izle/")) {
                return@forEach
            }

            val episodeNumber =
                slide.attr("data-episode")
                    .toIntOrNull()
                    ?: 1

            val seasonNumber =
                slide.attr("data-season")
                    .toIntOrNull()
                    ?: 1

            val episodeTitle =
                slide.attr("data-title")
                    .trim()
                    .ifBlank {
                        "Bölüm $episodeNumber"
                    }

            val episodePoster =
                slide.attr("data-poster")
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        absoluteUrl(it)
                    }

            episodes +=
                newEpisode(episodeUrl) {

                    name = episodeTitle

                    season = seasonNumber

                    episode = episodeNumber

                    posterUrl =
                        episodePoster
                            ?: poster
                }
        }

        /*
         * Bazı sayfalarda v-slide yerine
         * data-url taşıyan başka bölüm elemanları olabilir.
         */
        if (episodes.isEmpty()) {

            document.select(
                "[data-url*='/izle/'][data-episode]"
            ).forEach { element ->

                val rawUrl =
                    element.attr("data-url")
                        .trim()

                if (rawUrl.isBlank()) {
                    return@forEach
                }

                val episodeUrl =
                    absoluteUrl(rawUrl)

                if (!episodeUrl.contains("/izle/")) {
                    return@forEach
                }

                val episodeNumber =
                    element
                        .attr("data-episode")
                        .toIntOrNull()
                        ?: 1

                val seasonNumber =
                    element
                        .attr("data-season")
                        .toIntOrNull()
                        ?: 1

                val episodeTitle =
                    element
                        .attr("data-title")
                        .trim()
                        .ifBlank {
                            "Bölüm $episodeNumber"
                        }

                episodes +=
                    newEpisode(episodeUrl) {

                        name = episodeTitle

                        season = seasonNumber

                        episode = episodeNumber

                        posterUrl = poster
                    }
            }
        }

        /*
         * Son güvenlik katmanı:
         * Eğer dizi sayfasında sadece /izle/ linkleri
         * varsa onları da bölüm olarak kabul ediyoruz.
         */
        if (episodes.isEmpty()) {

            document.select(
                "a[href*='/izle/']"
            ).forEach { element ->

                val episodeUrl =
                    element
                        .attr("abs:href")
                        .trim()

                if (!episodeUrl.contains("/izle/")) {
                    return@forEach
                }

                val episodeNumber =
                    Regex(
                        "[?&]e=(\\d+)"
                    )
                        .find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                val seasonNumber =
                    Regex(
                        "[?&]s=(\\d+)"
                    )
                        .find(episodeUrl)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                val episodeTitle =
                    element.text()
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                        .trim()
                        .ifBlank {
                            "Bölüm $episodeNumber"
                        }

                episodes +=
                    newEpisode(episodeUrl) {

                        name = episodeTitle

                        season = seasonNumber

                        episode = episodeNumber

                        posterUrl = poster
                    }
            }
        }

        /*
         * Aynı bölümü birden fazla selector yakalarsa
         * Episode.data üzerinden tekrarları kaldırıyoruz.
         */
        val uniqueEpisodes =
            episodes
                .distinctBy { it.data }
                .sortedWith(
                    compareBy<Episode> {
                        it.season ?: 1
                    }.thenBy {
                        it.episode ?: 1
                    }
                )

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
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        /*
         * data artık doğrudan gerçek bölüm URL'sidir:
         *
         * /izle/disi-kurt-gelin?s=1&e=1
         */
        val episodeDocument =
            app.get(data).document

        /*
         * Sitenin gerçek player kaynağı:
         *
         * .lazy-player[data-src]
         */
        val embedUrls =
            LinkedHashSet<String>()

        episodeDocument
            .select(
                ".lazy-player[data-src]"
            )
            .forEach { element ->

                val value =
                    element.attr("data-src")
                        .trim()

                if (value.isNotBlank()) {
                    embedUrls +=
                        absoluteUrl(value)
                }
            }

        /*
         * Fallback olarak doğrudan iframe varsa onu da al.
         */
        episodeDocument
            .select(
                "iframe[src]"
            )
            .forEach { iframe ->

                val value =
                    iframe.attr("abs:src")
                        .trim()

                if (value.isNotBlank()) {
                    embedUrls += value
                }
            }

        if (embedUrls.isEmpty()) {
            return false
        }

        var linkFound = false

        /*
         * Önce sitenin kendi embed sayfasını açıyoruz.
         *
         * embed.php bir CloudStream extractor URL'si
         * değildir. Bu nedenle onu doğrudan
         * loadExtractor() içine vermiyoruz.
         */
        for (embedUrl in embedUrls) {

            try {

                val embedDocument =
                    app.get(
                        embedUrl,
                        referer = data
                    ).document

                /*
                 * Embed sayfasındaki iframe'ler.
                 */
                val iframeUrls =
                    LinkedHashSet<String>()

                embedDocument
                    .select(
                        "iframe[src]"
                    )
                    .forEach { iframe ->

                        val iframeUrl =
                            iframe
                                .attr("abs:src")
                                .trim()

                        if (
                            iframeUrl.isNotBlank()
                        ) {
                            iframeUrls +=
                                iframeUrl
                        }
                    }

                /*
                 * data-src kullanılan iframe/player.
                 */
                embedDocument
                    .select(
                        "iframe[data-src]"
                    )
                    .forEach { iframe ->

                        val iframeUrl =
                            absoluteUrl(
                                iframe
                                    .attr("data-src")
                                    .trim()
                            )

                        if (
                            iframeUrl.isNotBlank()
                        ) {
                            iframeUrls +=
                                iframeUrl
                        }
                    }

                /*
                 * Doğrudan video kaynakları.
                 */
                embedDocument
                    .select(
                        "video source[src], video[src]"
                    )
                    .forEach { video ->

                        val videoUrl =
                            video
                                .attr("abs:src")
                                .trim()

                        if (
                            videoUrl.isNotBlank()
                        ) {

                            callback(
                                newExtractorLink(
                                    name,
                                    "DramaDizilerim",
                                    videoUrl,
                                    data,
                                    false
                                )
                            )

                            linkFound = true
                        }
                    }

                /*
                 * Gerçek iframe bulunduysa
                 * CloudStream extractor sistemine veriyoruz.
                 */
                for (iframeUrl in iframeUrls) {

                    try {

                        val extracted =
                            loadExtractor(
                                iframeUrl,
                                data,
                                subtitleCallback,
                                callback
                            )

                        if (extracted) {
                            linkFound = true
                        }

                    } catch (_: Exception) {
                        // Bir player çalışmazsa diğerini dene.
                    }
                }

            } catch (_: Exception) {
                // Bir embed kaynağı başarısızsa diğerini dene.
            }
        }

        return linkFound
    }
}
