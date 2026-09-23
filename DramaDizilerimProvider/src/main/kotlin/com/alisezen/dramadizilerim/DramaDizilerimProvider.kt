package com.alisezen.dramadizilerim

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DramaDizilerimProvider : MainAPI() {
    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"

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
            // Fallback below.
        }

        if (results.isEmpty()) {
            val slug = query.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9çğıöşü -]"), "")
                .replace(Regex("\\s+"), "-")

            val url = "$mainUrl/dizi/$slug"

            results += newTvSeriesSearchResponse(
                query.trim(),
                url,
                TvType.TvSeries
            )
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("meta[property='og:title']")
            ?.attr("content")
            ?: doc.title().substringBefore(" | ").trim()

        val poster = doc.selectFirst("meta[property='og:image']")
            ?.attr("content")

        val description = doc.selectFirst("meta[property='og:description']")
            ?.attr("content")

        val episodes = mutableListOf<Episode>()

        doc.select(".v-slide[data-url], section.v-slide[data-url]")
            .forEach { slide ->

                val epUrl = slide.attr("data-url")
                val epTitle = slide.attr("data-title")
                    .ifBlank { slide.text().trim() }

                val seasonNumber =
                    slide.attr("data-season").toIntOrNull() ?: 1

                val episodeNumber =
                    slide.attr("data-episode").toIntOrNull() ?: 1

                if (epUrl.isNotBlank()) {
                    episodes += newEpisode(epUrl) {
                        name = epTitle
                        season = seasonNumber
                        episode = episodeNumber
                    }
                }
            }

        if (episodes.isEmpty()) {
            doc.select("a[href*='/izle/']").forEach { a ->

                val epUrl = a.attr("abs:href")

                if (epUrl.isNotBlank()) {
                    episodes += newEpisode(epUrl) {
                        name = a.text().trim()
                        season = 1
                        episode = 1
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
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

        val embed = doc.selectFirst(".lazy-player[data-src]")
            ?.attr("data-src")
            ?: return false

        return loadExtractor(
            embed,
            data,
            subtitleCallback,
            callback
        )
    }
}
