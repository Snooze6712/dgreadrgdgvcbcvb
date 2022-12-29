package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.AltaFinzioneProvider.Companion.decryptBase64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.util.*


class AltaFinzioneProvider : MainAPI() {

    override var lang = "it"
    override var mainUrl = decryptBase64("aHR0cHM6Ly9hbHRhZGVmaW5pemlvbmVjb21tdW5pdHkub25saW5l")
    override var name = "AltaFinzione"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        var bearer: String? = ""
        fun decryptBase64(base64EncryptedString: String) = String(Base64.decode(base64EncryptedString, Base64.DEFAULT), Charsets.UTF_8)
    }

    override val mainPage = mainPageOf(
        Pair("$mainUrl/api/category/film?type=movie&orderBy=i_piu_votati_dellultimo_mese&page=", "Più votati"),
        Pair("$mainUrl/api/category/nuove-uscite?page=", "Nuove uscite"),
        Pair("$mainUrl/api/category/film?&orderBy=i_piu_visti&page=", "Film"),
        Pair("$mainUrl/api/category/serie-tv?&orderBy=i_piu_visti&page=", "Serie Tv"),
        Pair("$mainUrl/api/category/netflix?&orderBy=i_piu_visti&page=", "Netflix"),
        Pair("$mainUrl/api/category/disney?&orderBy=i_piu_visti&page=", "Disney"),
        Pair("$mainUrl/api/category/amazon-prime?&orderBy=i_piu_visti&page=", "Amazon Prime"),
        Pair("$mainUrl/api/category/appletv?&orderBy=i_piu_visti&page=", "Apple Tv"),
        Pair("$mainUrl/api/category/anime?&orderBy=i_piu_visti&page=", "Anime")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val response = tryParseJson<MainPageApi>(app.get(url).text)
        val home = response?.data?.map { movie ->
            movie.toSearchResult()
        } ?: throw ErrorLoadingException()
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = home.isNotEmpty())
    }

    private fun Datum.toSearchResult(): SearchResponse {
        val link = "$mainUrl/p/${this.slug}"
        val quality = this.finalQuality ?: ""
        val poster = this.posterImage
        val type = when (this.type) {
            "movie" -> TvType.Movie
            "tvshow" -> TvType.TvSeries
            else -> TvType.Others
        }
        return if (type==TvType.Movie) {
            newMovieSearchResponse(name = this.title ?: "", link, TvType.Movie) {
                addPoster(poster)
                addQuality(quality)
            }
        } else{
            newTvSeriesSearchResponse(name = this.title ?: "", link, TvType.TvSeries) {
                addPoster(poster)
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/api/search?search=${query}").parsed<MainPageApi>()
        return response.data?.map { movie ->
            movie.toSearchResult()
        } ?: emptyList()
    }

    private fun findTag(id: Long, list: UiData): String {
        return list.featuredCategories?.firstOrNull { it.id == id }?.name
            ?: list.movieCategories?.firstOrNull { it.id == id }?.name
            ?: list.tvprogramCategories?.firstOrNull { it.id == id }?.name
            ?: list.tvshowCategories?.firstOrNull { it.id == id }?.name ?: ""
    }

    override suspend fun load(url: String): LoadResponse {
        val page = app.get(url.replace("/p/", "/api/posts/slug/")).parsedSafe<PostsApi>()?.post
            ?: throw ErrorLoadingException()

        val type = when (page.type) {
            "movie" -> TvType.Movie
            "tvshow" -> TvType.TvSeries
            else -> TvType.Others
        }

        //to load them at the same time
        val linksUtils = listOf(
            "$mainUrl/api/posts/terms/${page.id}",
            "$mainUrl/api/posts/related/${page.slug}",
            "$mainUrl/api/generic/ui",
        ).apmap { a -> a to app.get(a) }.toMap()

        val actorsData =
            linksUtils["$mainUrl/api/posts/terms/${page.id}"]?.parsedSafe<ActorsData>()?.terms?.actors?.map {
                Actor(it.value ?: "")
            } ?: emptyList()

        val recommendation =
            linksUtils["$mainUrl/api/posts/related/${page.slug}"]?.parsedSafe<MainPageApi>()?.data?.map { movie ->
                movie.toSearchResult()
            }

        val tagsData = linksUtils["$mainUrl/api/generic/ui"]?.parsedSafe<UiData>()

        val trailer = page.youtubeTrailerID?.map { "https://www.youtube.com/watch?v=${it}" }?.randomOrNull()

        val tags = if (tagsData != null) {
            page.categoriesIDS?.map { id -> findTag(id, tagsData) }
        } else {
            emptyList()
        }

        if (type == TvType.Movie) {
            return newMovieLoadResponse(
                name = page.title ?: "",
                url = "$mainUrl/p/${page.slug}",
                type = type,
                dataUrl = "$mainUrl/api/post/urls/stream/${page.slug}"
            ) {
                this.year = page.year?.toIntOrNull()
                this.plot = page.plot
                this.recommendations = recommendation
                this.tags = tags
                addTrailer(trailer)
                addPoster(page.largeImage)
                addDuration(page.runtime)
                addRating(page.rating?.toInt())
                addActors(actorsData)
                addTMDbId(page.tmdbID)
            }
        } else {
            val episodesPage =
                app.get("$mainUrl/api/posts/seasons/${page.slug}").parsedSafe<EpisodesApi>()
            val episodesDatum = getEpisodes(episodesPage, page.slug)
            val names = episodesPage?.seasons?.mapIndexed { number, season ->
                SeasonData(
                    number + 1,
                    season.seasonLabel
                )
            } ?: emptyList()
            return newTvSeriesLoadResponse(
                name = page.title ?: "",
                url = "$mainUrl/p/${page.slug}",
                type = type,
                episodes = episodesDatum
            ) {
                this.year = page.year?.toIntOrNull()
                this.plot = page.plot
                this.recommendations = recommendation
                this.tags = tags
                addTrailer("https://www.youtube.com/watch?v=${page.youtubeTrailerID}")
                addPoster(page.largeImage)
                addDuration(page.runtime)
                addRating(page.rating?.toInt())
                addActors(actorsData)
                addSeasonNames(names)
                addTMDbId(page.tmdbID)
            }
        }
    }

    private fun getEpisodes(episodesPage : EpisodesApi?, slug: String?): MutableList<Episode> {
        val episodesDatum = mutableListOf<Episode>()
        episodesPage?.seasons?.forEachIndexed { season, seasonData ->
            if (seasonData.episodes is Map<*, *>) {
                // episodes are in a map
                seasonData.episodes.map {
                    val label = (it.value as Map<*, *>)["label"]
                    val number = (it.value as Map<*, *>)["number"] as Int
                    val episode = newEpisode("$mainUrl/api/post/urls/stream/$slug/$season/${number}") {
                        this.name = "Episodio $label"
                        this.season = season + 1
                        this.episode = number.plus(1)
                    }
                    episodesDatum.add(episode)
                }
            } else if (seasonData.episodes is List<*>) {
                // episodes are in a list
                seasonData.episodes.map {
                    val label = (it as Map<*, *>)["label"]
                    val number = it["number"] as Int
                    val episode = newEpisode("$mainUrl/api/post/urls/stream/$slug/$season/${number}") {
                        this.name = "Episodio $label"
                        this.season = season + 1
                        this.episode = number.plus(1)
                    }
                    episodesDatum.add(episode)
                }
            }
        }
        return episodesDatum
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (bearer.isNullOrBlank()) getBearer()

        val doc = app.get(
            data, headers = mapOf(
                Pair(
                    "Authorization",
                    "Bearer $bearer"
                )
            )
        ).parsedSafe<LinkApi>()
        doc?.streams?.forEach { stream ->
            stream.url?.let { url ->
                callback.invoke(
                    ExtractorLink(
                        source = "AltaFinzione",
                        name = stream.downloadSize ?: "",
                        url = url,
                        isM3u8 = false,
                        referer = mainUrl,
                        quality = getQualityFromName(stream.resolution?.name)
                    )
                )
            }
        }
        return true
    }
}

// Method 1: get the bearer from Github
private suspend fun getBearer(){
    AltaFinzioneProvider.bearer = decryptBase64(app.get("https://raw.githubusercontent.com/Snooze6712/njiogaerhshrtgiokm/main/O0O0000000000O0O0.txt").text)
}

private data class MainPageApi(
    @JsonProperty("current_page") val currentPage: Long? = null,
    val data: List<Datum>? = null,
    @JsonProperty("first_page_url") val firstPageURL: String? = null,
    val from: Long? = null,
    @JsonProperty("last_page") val lastPage: Long? = null,
    @JsonProperty("last_page_url") val lastPageURL: String? = null,
    val links: List<Link>? = null,
    @JsonProperty("next_page_url") val nextPageURL: String? = null,
    val path: String? = null,
    @JsonProperty("per_page") val perPage: Long? = null,
    @JsonProperty("prev_page_url") val prevPageURL: Any? = null,
    val to: Long? = null,
    val total: Long? = null
)

private data class PostsApi(
    val status: Boolean? = null,
    val post: Datum? = null
)

private data class Datum(
    val id: Long? = null,
    val type: String? = null,
    val title: String? = null,
    val plot: String? = null,
    @JsonProperty("imdb_id") val imdbID: String? = null,
    @JsonProperty("tmdb_id") val tmdbID: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("youtube_trailer_id") val youtubeTrailerID: String? = null,
    @JsonProperty("total_episodes_count") val totalEpisodesCount: Any? = null,
    @JsonProperty("old_total_episodes_count") val oldTotalEpisodesCount: Any? = null,
    val slug: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
    @JsonProperty("updated_at") val updatedAt: String? = null,
    val rating: Long? = null,
    val runtime: String? = null,
    val quality: List<String>? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("comments_count") val commentsCount: Long? = null,
    @JsonProperty("seasons_count") val seasonsCount: Long? = null,
    @JsonProperty("viewed_posts_count") val viewedPostsCount: Long? = null,
    @JsonProperty("poster_image") val posterImage: String? = null,
    @JsonProperty("small_image") val smallImage: String? = null,
    @JsonProperty("large_image") val largeImage: String? = null,
    @JsonProperty("large_image_mobile") val largeImageMobile: String? = null,
    @JsonProperty("large_image_mobile_lazy") val largeImageMobileLazy: String? = null,
    val year: String? = null,
    @JsonProperty("final_quality") val finalQuality: String? = null,
    @JsonProperty("poster_slider_image_src") val posterSliderImageSrc: String? = null,
    @JsonProperty("poster_image_src") val posterImageSrc: String? = null,
    @JsonProperty("title_without_special_characters") val titleWithoutSpecialCharacters: String? = null,
    @JsonProperty("quality_score") val qualityScore: QualityScore? = null,
    @JsonProperty("categories_ids") val categoriesIDS: List<Long>? = null,
    @JsonProperty("show_next_episode_flag") val showNextEpisodeFlag: Boolean? = null,
    @JsonProperty("imdb_url") val imdbURL: String? = null
)

private data class QualityScore(
    @JsonProperty("video_score") val videoScore: Long? = null,
    @JsonProperty("audio_score") val audioScore: Long? = null
)

private data class Link(
    val url: String? = null,
    val label: String? = null,
    val active: Boolean? = null
)

private data class ActorsData(
    val status: Boolean? = null,
    val terms: Terms? = null
)

private data class Terms(
    val actors: List<Actor>? = null,
    val writers: List<Actor>? = null,
    val directors: List<Actor>? = null
)

private data class Actor(
    val id: Long? = null,
    @JsonProperty("taxonomy_id") val taxonomyID: Long? = null,
    @JsonProperty("cast_as") val castAs: String? = null,
    val value: String? = null,
    val slug: String? = null,
    @JsonProperty("deleted_at") val deletedAt: Any? = null,
    val pivot: Pivot? = null,
    val taxonomy: Taxonomy? = null
)

private data class Pivot(
    @JsonProperty("post_id") val postID: Long? = null,
    @JsonProperty("term_id") val termID: Long? = null
)

private data class Taxonomy(
    val id: Long? = null,
    val name: String? = null,
    @JsonProperty("singular_label") val singularLabel: String? = null,
    @JsonProperty("multiple_label") val multipleLabel: String? = null,
    val slug: String? = null,
    @JsonProperty("deleted_at") val deletedAt: Any? = null
)

private data class UiData(
    @JsonProperty("featured_categories") val featuredCategories: List<Category>? = null,
    @JsonProperty("movie_categories") val movieCategories: List<Category>? = null,
    @JsonProperty("tvshow_categories") val tvshowCategories: List<Category>? = null,
    @JsonProperty("tvprogram_categories") val tvprogramCategories: List<Category>? = null,
    val qualities: List<String>? = null,
    val years: List<Long>? = null
)

private data class Category(
    val id: Long? = null,
    val name: String? = null,
    @JsonProperty("homepage_name") val homepageName: String? = null,
    val slug: String? = null,
    @JsonProperty("movies_sort") val moviesSort: Long? = null,
    @JsonProperty("tvshows_sort") val tvshowsSort: Long? = null,
    @JsonProperty("tvprogram_sort") val tvprogramSort: Long? = null,
    @JsonProperty("image_url") val imageURL: String? = null
)


private data class EpisodesApi(
    val seasons: List<Season>? = null,
)

private data class Season(
    val episodes: Any? = null,
    @JsonProperty("season_label") val seasonLabel: String? = null
)

private data class LinkApi(
    val status: Boolean? = null,
    val streams: List<Stream>? = null,
    val subtitles: Boolean? = null,
    val title: String? = null,
    val id: Long? = null,
    @JsonProperty("backdrop_url") val backdropURL: String? = null,
    @JsonProperty("poster_url") val posterURL: String? = null
)


private data class Stream(
    val url: String? = null,
    @JsonProperty("download_size") val downloadSize: String? = null,
    val length: Long? = null,
    @JsonProperty("audio_codec") val audioCodec: String? = null,
    @JsonProperty("season_label") val seasonLabel: String? = null,
    @JsonProperty("episode_label") val episodeLabel: String? = null,
    @JsonProperty("season_number") val seasonNumber: String? = null,
    @JsonProperty("episode_number") val episodeNumber: String? = null,
    val resolution: Resolution? = null,
    val selected: Boolean? = null,
    @JsonProperty("need_upgrade") val needUpgrade: Boolean? = null
)

private data class Resolution(
    val id: Long? = null,
    val name: String? = null,
    @JsonProperty("is_default") val isDefault: Boolean? = null,
    @JsonProperty("stream_sort") val streamSort: Long? = null,
    @JsonProperty("stream_enabled") val streamEnabled: Long? = null,
    @JsonProperty("download_sort") val downloadSort: Long? = null,
    @JsonProperty("download_enabled") val downloadEnabled: Long? = null,
    val height: Long? = null
)
