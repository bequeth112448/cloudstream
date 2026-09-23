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

    /*
     * URL'yi path + query olacak şekilde normalize eder,
     * anchor'ı atar, sondaki '/' işaretini temizler.
     * loadLinks() içinde doğru bölümü (v-slide) bulmak
     * için kullanılıyor.
     */
    private fun normalizedPathAndQuery(
        rawUrl: String
    ): Pair<String, String> {

        val withoutAnchor =
            rawUrl.substringBefore("#")

        val path =
            withoutAnchor
                .substringBefore("?")
                .trimEnd('/')

        val query =
            withoutAnchor.substringAfter("?", "")

        return path to query
    }

    private fun posterFromElement(
        element: org.jsoup.nodes.Element
    ): String? {

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
            element
                .selectFirst("img[alt]")
                ?.attr("alt")
                ?.trim()

        if (!title.isNullOrBlank()) {
            return title
        }

        return element.text()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /*
     * Bir .v-slide elemanından Episode nesnesi üretir.
     * load() içinde birden çok yerde kullanılıyor.
     */
    private fun episodeFromSlide(
        slide: org.jsoup.nodes.Element,
        fallbackPoster: String?
    ): Episode? {

        val rawUrl =
            slide.attr("data-url").trim()

        if (rawUrl.isBlank()) {
            return null
        }

        val episodeUrl =
            absoluteUrl(rawUrl)

        if (
            episodeUrl.isBlank() ||
            !episodeUrl.contains("/izle/")
        ) {
            return null
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
                .takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(it) }

        return newEpisode(episodeUrl) {

            name = episodeTitle

            season = seasonNumber

            episode = episodeNumber

            posterUrl =
                episodePoster
                    ?: fallbackPoster
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

        document.select(
            "a[href*='/dizi/']"
        ).forEach { element ->

            val url =
                element
                    .attr("abs:href")
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

        val searchQuery =
            query.trim()

        if (searchQuery.isBlank()) {
            return emptyList()
        }

        try {

            /*
             * DÜZELTME:
             * Önceden "$mainUrl/series" adresine istek
             * atılıyordu; böyle bir sayfa olmadığı için
             * arama hep boş dönüyordu.
             *
             * Dizi listesinin gerçek adresi "$mainUrl/dizi".
             */
            val document =
                app.get("$mainUrl/dizi").document

            document.select(
                "a[href*='/dizi/']"
            ).forEach { element ->

                val url =
                    element
                        .attr("abs:href")
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
            /*
             * Siteye erişilemezse boş sonuç döndür.
             * Sahte URL oluşturulmuyor.
             */
        }

        return results
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        var document =
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
         * 1. Öncelik:
         * Elimizdeki dokümanda doğrudan .v-slide[data-url]
         * varsa (örn. url zaten bir /izle/ sayfasıysa),
         * bölümleri doğrudan buradan al.
         */
        document.select(
            ".v-slide[data-url]"
        ).forEach { slide ->

            episodeFromSlide(slide, poster)
                ?.let { episodes += it }
        }

        /*
         * 2. DÜZELTME:
         * "/dizi/{slug}" sayfasında genelde .v-slide yok;
         * sadece dizi tanıtımı ve "İzle" butonu bulunuyor.
         * Gerçek bölüm listesi (TÜM sezon/bölümler tek
         * seferde) ilk bölümün "/izle/..." sayfasında
         * .v-slide olarak geliyor.
         *
         * Bu yüzden episodes hâlâ boşsa: sayfadaki ilk
         * "/izle/" linkini bulup o sayfayı ayrıca çekiyoruz
         * ve TÜM .v-slide'ları oradan alıyoruz.
         */
        if (episodes.isEmpty()) {

            val firstEpisodeUrl =
                document
                    .selectFirst(
                        "[data-url*='/izle/']"
                    )
                    ?.attr("data-url")
                    ?.trim()
                    ?.let { absoluteUrl(it) }
                    ?: document
                        .selectFirst(
                            "a[href*='/izle/']"
                        )
                        ?.attr("abs:href")
                        ?.trim()

            if (!firstEpisodeUrl.isNullOrBlank()) {

                try {

                    val episodeDocument =
                        app.get(firstEpisodeUrl).document

                    episodeDocument.select(
                        ".v-slide[data-url]"
                    ).forEach { slide ->

                        episodeFromSlide(slide, poster)
                            ?.let { episodes += it }
                    }

                    /*
                     * Eğer bu ikinci sayfada poster/açıklama
                     * daha iyiyse (ilk sayfa /dizi/ sayfasıysa
                     * genelde daha iyi bilgi orada olur), bu
                     * kısmı olduğu gibi bırakıyoruz; sadece
                     * bölüm verisi için ek istek yaptık.
                     */

                } catch (_: Exception) {
                    /*
                     * İkinci istek başarısızsa, alttaki
                     * fallback'lere devam edilecek.
                     */
                }
            }
        }

        /*
         * 3. Fallback:
         * data-url + data-episode (v-slide olmayan
         * ama yine de data attribute'lu elemanlar)
         */
        if (episodes.isEmpty()) {

            document.select(
                "[data-url*='/izle/'][data-episode]"
            ).forEach { element ->

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
                    newEpisode(episodeUrl) {

                        name = episodeTitle

                        season = seasonNumber

                        episode = episodeNumber

                        posterUrl = poster
                    }
            }
        }

        /*
         * 4. Fallback:
         * Normal /izle/ linkleri (yalnızca tek bölüm
         * bulunabilir, ama hiçbir şey bulunamamasından
         * iyidir)
         */
        if (episodes.isEmpty()) {

            document.select(
                "a[href*='/izle/']"
            ).forEach { element ->

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
                    newEpisode(episodeUrl) {

                        name = episodeTitle

                        season = seasonNumber

                        episode = episodeNumber

                        posterUrl = poster
                    }
            }
        }

        val uniqueEpisodes =
            episodes
                .distinctBy {
                    "${it.season ?: 1}-${it.episode ?: 1}-${it.name}"
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

        val episodeDocument =
            app.get(data).document

        val (targetPath, targetQuery) =
            normalizedPathAndQuery(data)

        /*
         * DÜZELTME (kritik bug):
         * "/izle/{slug}" sayfası dizinin TÜM bölümlerini
         * .v-slide elemanları olarak aynı anda içeriyor.
         * Önceden ".lazy-player[data-src]" class seçiciyle
         * SAYFADAKİ TÜM bölümlerin video linkleri toplanıp
         * hepsi extractor'a gönderiliyordu — yani 1. bölüm
         * açıldığında 40+ bölümün embed'i birden işleniyordu.
         *
         * Artık data-url'i (path + query) tam olarak eşleşen
         * TEK v-slide'ı buluyoruz ve sadece onun player'ını
         * kullanıyoruz.
         */
        val targetSlide =
            episodeDocument
                .select(".v-slide[data-url]")
                .firstOrNull { slide ->

                    val (slidePath, slideQuery) =
                        normalizedPathAndQuery(
                            slide.attr("data-url").trim()
                        )

                    slidePath == targetPath &&
                        slideQuery == targetQuery
                }

        val embedUrls =
            LinkedHashSet<String>()

        targetSlide
            ?.selectFirst(".lazy-player[data-src]")
            ?.attr("data-src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { embedUrls += absoluteUrl(it) }

        /*
         * Fallback: eşleşen v-slide bulunamazsa (sayfa
         * yapısı farklıysa ya da tek bölümlük bir sayfaysa)
         * sayfadaki iframe'lere bak. NOT: artık TÜM
         * .lazy-player'ları toplamıyoruz.
         */
        if (embedUrls.isEmpty()) {

            episodeDocument
                .select("iframe[src]")
                .forEach { iframe ->

                    val value =
                        iframe
                            .attr("abs:src")
                            .trim()

                    if (value.isNotBlank()) {
                        embedUrls += value
                    }
                }
        }

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

                        if (iframeUrl.isNotBlank()) {
                            iframeUrls += iframeUrl
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

                        if (raw.isNotBlank()) {

                            iframeUrls +=
                                absoluteUrl(raw)
                        }
                    }

                embedDocument
                    .select(
                        "video source[src], video[src]"
                    )
                    .forEach { video ->

                        val videoUrl =
                            video
                                .attr("abs:src")
                                .trim()

                        if (videoUrl.isNotBlank()) {

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
                        /*
                         * Bir player çalışmazsa
                         * diğer player denenir.
                         */
                    }
                }

            } catch (_: Exception) {
                /*
                 * Bir embed başarısızsa
                 * diğer embed denenir.
                 */
            }
        }

        return linkFound
    }
}
