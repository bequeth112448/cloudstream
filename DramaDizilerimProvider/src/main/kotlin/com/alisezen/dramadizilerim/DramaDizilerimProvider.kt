
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

        if (value.isBlank()) return ""

        return when {
            value.startsWith("http://") ||
            value.startsWith("https://") -> value

            value.startsWith("//") -> "https:$value"

            value.startsWith("/") -> "$mainUrl$value"

            else -> "$mainUrl/$value"
        }
    }

    private fun normalizedPathAndQuery(
        rawUrl: String
    ): Pair<String, String> {

        val url = rawUrl
            .trim()
            .substringBefore("#")

        val path = url
            .substringBefore("?")
            .trimEnd('/')

        val query = url
            .substringAfter("?", "")

        return path to query
    }

    private fun posterFromElement(
        element: org.jsoup.nodes.Element
    ): String? {

        val poster = element
            .attr("data-poster")
            .ifBlank {
                element.attr("data-src")
            }
            .ifBlank {
                element.attr("src")
            }
            .trim()

        if (poster.isBlank()) {
            return null
        }

        return absoluteUrl(poster)
    }

    private fun titleFromElement(
        element: org.jsoup.nodes.Element
    ): String {

        val dataTitle = element
            .attr("data-title")
            .trim()

        if (dataTitle.isNotBlank()) {
            return dataTitle
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        val imageTitle = element
            .selectFirst("img[alt]")
            ?.attr("alt")
            ?.trim()

        if (!imageTitle.isNullOrBlank()) {
            return imageTitle
        }

        return element
            .text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun episodeFromSlide(
        slide: org.jsoup.nodes.Element,
        fallbackPoster: String?
    ): Episode? {

        val rawUrl = slide
            .attr("data-url")
            .trim()

        if (rawUrl.isBlank()) {
            return null
        }

        val episodeUrl = absoluteUrl(rawUrl)

        if (
            episodeUrl.isBlank() ||
            !episodeUrl.contains("/izle/")
        ) {
            return null
        }

        val episodeNumber = slide
            .attr("data-episode")
            .toIntOrNull()
            ?: 1

        val seasonNumber = slide
            .attr("data-season")
            .toIntOrNull()
            ?: 1

        val episodeTitle = slide
            .attr("data-title")
            .trim()
            .ifBlank {
                "Bölüm $episodeNumber"
            }

        val episodePoster = slide
            .attr("data-poster")
            .trim()
            .takeIf {
                it.isNotBlank()
            }
            ?.let {
                absoluteUrl(it)
            }

        return newEpisode(episodeUrl) {

            name = episodeTitle
            season = seasonNumber
            episode = episodeNumber

            posterUrl =
                episodePoster ?: fallbackPoster
        }
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

        document
            .select("a[href*='/dizi/']")
            .forEach { element ->

                val url = element
                    .attr("abs:href")
                    .trim()

                if (
                    url.isBlank() ||
                    !url.contains("/dizi/")
                ) {
                    return@forEach
                }

                val normalizedUrl = url
                    .substringBefore("#")
                    .substringBefore("?")
                    .trimEnd('/')

                if (
                    normalizedUrl.isBlank() ||
                    !seen.add(normalizedUrl)
                ) {
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
                            ?.let {
                                posterFromElement(it)
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

        val searchQuery =
            query.trim()

        if (searchQuery.isBlank()) {
            return emptyList()
        }

        val results =
            mutableListOf<SearchResponse>()

        val seen =
            HashSet<String>()

        try {

            val document =
                app.get("$mainUrl/dizi").document

            document
                .select("a[href*='/dizi/']")
                .forEach { element ->

                    val url = element
                        .attr("abs:href")
                        .trim()

                    if (
                        url.isBlank() ||
                        !url.contains("/dizi/")
                    ) {
                        return@forEach
                    }

                    val normalizedUrl = url
                        .substringBefore("#")
                        .substringBefore("?")
                        .trimEnd('/')

                    if (
                        normalizedUrl.isBlank() ||
                        !seen.add(normalizedUrl)
                    ) {
                        return@forEach
                    }

                    val title =
                        titleFromElement(element)
                            .trim()

                    if (title.isBlank()) {
                        return@forEach
                    }

                    if (
                        !title.contains(
                            searchQuery,
                            ignoreCase = true
                        )
                    ) {
                        return@forEach
                    }

                    val poster =
                        posterFromElement(element)
                            ?: element
                                .selectFirst("img")
                                ?.let {
                                    posterFromElement(it)
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
                ?.substringBefore(" Sezon ")
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

        if (poster.isNullOrBlank()) {

            poster =
                document
                    .selectFirst("[data-poster]")
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
                                    image.attr("abs:data-src")
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
         * Öncelikle mevcut sayfadaki .v-slide
         * elemanlarını kontrol ediyoruz.
         */
        document
            .select(".v-slide[data-url]")
            .forEach { slide ->

                episodeFromSlide(
                    slide,
                    poster
                )?.let {
                    episodes += it
                }
            }

        /*
         * /dizi/ sayfasında bölüm listesi yoksa,
         * ilk /izle/ bağlantısına gidiyoruz.
         */
        if (episodes.isEmpty()) {

            val firstEpisodeUrl =
                document
                    .selectFirst(
                        "[data-url*='/izle/']"
                    )
                    ?.attr("data-url")
                    ?.trim()
                    ?.let {
                        absoluteUrl(it)
                    }
                    ?: document
                        .selectFirst(
                            "a[href*='/izle/']"
                        )
                        ?.attr("abs:href")
                        ?.trim()

            if (!firstEpisodeUrl.isNullOrBlank()) {

                try {

                    val episodeDocument =
                        app.get(
                            firstEpisodeUrl
                        ).document

                    episodeDocument
                        .select(".v-slide[data-url]")
                        .forEach { slide ->

                            episodeFromSlide(
                                slide,
                                poster
                            )?.let {
                                episodes += it
                            }
                        }

                } catch (_: Exception) {
                }
            }
        }

        /*
         * Alternatif data-url yapısı.
         */
        if (episodes.isEmpty()) {

            document
                .select(
                    "[data-url*='/izle/']"
                )
                .forEach { element ->

                    val rawUrl =
                        element
                            .attr("data-url")
                            .trim()

                    if (rawUrl.isBlank()) {
                        return@forEach
                    }

                    val episodeUrl =
                        absoluteUrl(rawUrl)

                    if (
                        episodeUrl.isBlank() ||
                        !episodeUrl.contains("/izle/")
                    ) {
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
                        newEpisode(
                            episodeUrl
                        ) {

                            name =
                                episodeTitle

                            season =
                                seasonNumber

                            episode =
                                episodeNumber

                            posterUrl =
                                poster
                        }
                }
        }

        /*
         * Son fallback:
         * normal /izle/ bağlantıları.
         */
        if (episodes.isEmpty()) {

            document
                .select("a[href*='/izle/']")
                .forEach { element ->

                    val episodeUrl =
                        element
                            .attr("abs:href")
                            .trim()

                    if (
                        episodeUrl.isBlank() ||
                        !episodeUrl.contains("/izle/")
                    ) {
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
                        element
                            .text()
                            .replace(
                                Regex("\\s+"),
                                " "
                            )
                            .trim()
                            .ifBlank {
                                "Bölüm $episodeNumber"
                            }

                    episodes +=
                        newEpisode(
                            episodeUrl
                        ) {

                            name =
                                episodeTitle

                            season =
                                seasonNumber

                            episode =
                                episodeNumber

                            posterUrl =
                                poster
                        }
                }
        }

        val uniqueEpisodes =
            episodes
                .distinctBy {
                    Triple(
                        it.season ?: 1,
                        it.episode ?: 1,
                        it.data
                    )
                }
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

        try {

            val episodeDocument =
                app.get(data).document

            val (
                targetPath,
                targetQuery
            ) =
                normalizedPathAndQuery(data)

            /*
             * Önce tam URL eşleşen v-slide'ı bul.
             */
            val targetSlide =
                episodeDocument
                    .select(
                        ".v-slide[data-url]"
                    )
                    .firstOrNull { slide ->

                        val (
                            slidePath,
                            slideQuery
                        ) =
                            normalizedPathAndQuery(
                                slide
                                    .attr("data-url")
                                    .trim()
                            )

                        slidePath == targetPath &&
                            slideQuery == targetQuery
                    }

            val embedUrls =
                LinkedHashSet<String>()

            /*
             * Eşleşen slide'ın player'ı.
             */
            targetSlide
                ?.selectFirst(
                    ".lazy-player[data-src]"
                )
                ?.attr("data-src")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    embedUrls +=
                        absoluteUrl(it)
                }

            /*
             * Bazı sayfalarda player doğrudan iframe
             * olarak bulunabilir.
             */
            if (
                targetSlide != null &&
                embedUrls.isEmpty()
            ) {

                targetSlide
                    .selectFirst(
                        "iframe[src]"
                    )
                    ?.attr("abs:src")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        embedUrls += it
                    }
            }

            /*
             * Slide bulunamazsa sadece sayfanın doğrudan
             * iframe'lerine bak.
             */
            if (embedUrls.isEmpty()) {

                episodeDocument
                    .select("iframe[src]")
                    .forEach { iframe ->

                        val iframeUrl =
                            iframe
                                .attr("abs:src")
                                .trim()

                        if (
                            iframeUrl.isNotBlank()
                        ) {
                            embedUrls +=
                                iframeUrl
                        }
                    }
            }

            /*
             * Video yoksa false.
             */
            if (embedUrls.isEmpty()) {
                return false
            }

            var linkFound = false

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
                        .select("iframe[src]")
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

                    embedDocument
                        .select(
                            "iframe[data-src]"
                        )
                        .forEach { iframe ->

                            val raw =
                                iframe
                                    .attr("data-src")
                                    .trim()

                            if (
                                raw.isNotBlank()
                            ) {
                                iframeUrls +=
                                    absoluteUrl(raw)
                            }
                        }

                    /*
                     * Doğrudan video varsa.
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
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        referer = data
                                    }
                                )

                                linkFound = true
                            }
                        }

                    /*
                     * Extractor ile iframe'leri çöz.
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
                        }
                    }

                } catch (_: Exception) {
                }
            }

            return linkFound

        } catch (_: Exception) {

            return false
        }
    }
}
