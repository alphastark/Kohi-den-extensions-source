package eu.kanade.tachiyomi.animeextension.en.hianime.filters

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

// Adapted from ZoroFilters.kt
object HiAnimeFilters {

    open class QueryUriSelectFilter(
        displayName: String,
        val query: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        defaultValue,
    ) {
        fun getValue() = vals[state].second
    }

    open class QueryUriGroupFilter(
        displayName: String,
        val query: String,
        vals: List<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        displayName,
        vals.map { CheckBoxVal(it.first, it.second) },
    ) {
        fun getValue() = state
            .filter { it.state }
            .joinToString(",") { it.value }
    }

    private class CheckBoxVal(name: String, val value: String) : AnimeFilter.CheckBox(name, false)

    // Filters (copied from Zoro, verify against hianime.to/filter)
    private val TYPE_LIST = arrayOf(
        Pair("All", "all"),
        Pair("Movie", "1"),
        Pair("TV", "2"),
        Pair("OVA", "3"),
        Pair("ONA", "4"),
        Pair("Special", "5"),
        Pair("Music", "6"),
    )

    private val STATUS_LIST = arrayOf(
        Pair("All", "all"),
        Pair("Finished Airing", "1"),
        Pair("Currently Airing", "2"),
        Pair("Not Yet Aired", "3"),
    )

    private val RATED_LIST = arrayOf(
        Pair("All", "all"),
        Pair("G - All Ages", "1"),
        Pair("PG - Children", "2"),
        Pair("PG-13 - Teens 13 or older", "3"),
        Pair("R - 17+ (violence & profanity)", "4"),
        Pair("R+ - Mild Nudity", "5"),
    )

    private val SCORE_LIST = arrayOf(
        Pair("All", "all"),
        Pair("(1) Appalling", "1"),
        Pair("(2) Horrible", "2"),
        Pair("(3) Very Bad", "3"),
        Pair("(4) Bad", "4"),
        Pair("(5) Average", "5"),
        Pair("(6) Fine", "6"),
        Pair("(7) Good", "7"),
        Pair("(8) Very Good", "8"),
        Pair("(9) Great", "9"),
        Pair("(10) Masterpiece", "10"),
    )

    private val SEASON_LIST = arrayOf(
        Pair("All", "all"),
        Pair("Spring", "1"),
        Pair("Summer", "2"),
        Pair("Fall", "3"),
        Pair("Winter", "4"),
    )

    private val LANGUAGE_LIST = arrayOf(
        Pair("All", "all"),
        Pair("SUB", "1"),
        Pair("DUB", "2"),
        Pair("SUB & DUB", "3"),
    )

    private val SORT_LIST = arrayOf(
        Pair("Default", "default"),
        Pair("Recently Added", "recently_added"),
        Pair("Recently Updated", "recently_updated"),
        Pair("Score", "score"),
        Pair("Name A-Z", "name_az"),
        Pair("Released Date", "released_date"),
        Pair("Most Viewed", "most_viewed"),
    )

    private val GENRES_LIST = listOf(
        Pair("Action", "1"),
        Pair("Adventure", "2"),
        Pair("Cars", "3"),
        Pair("Comedy", "4"),
        Pair("Dementia", "5"),
        Pair("Demons", "6"),
        Pair("Drama", "7"),
        Pair("Ecchi", "8"),
        Pair("Fantasy", "9"),
        Pair("Game", "10"),
        Pair("Harem", "11"),
        Pair("Historical", "12"),
        Pair("Horror", "13"),
        Pair("Isekai", "40"),
        Pair("Josei", "14"),
        Pair("Kids", "15"),
        Pair("Magic", "16"),
        Pair("Martial Arts", "17"),
        Pair("Mecha", "18"),
        Pair("Military", "19"),
        Pair("Music", "20"),
        Pair("Mystery", "21"),
        Pair("Parody", "22"),
        Pair("Police", "23"),
        Pair("Psychological", "24"),
        Pair("Romance", "25"),
        Pair("Samurai", "26"),
        Pair("School", "27"),
        Pair("Sci-Fi", "28"),
        Pair("Seinen", "29"),
        Pair("Shoujo", "30"),
        Pair("Shoujo Ai", "31"),
        Pair("Shounen", "32"),
        Pair("Shounen Ai", "33"),
        Pair("Slice of Life", "34"),
        Pair("Space", "35"),
        Pair("Sports", "36"),
        Pair("Super Power", "37"),
        Pair("Supernatural", "38"),
        Pair("Thriller", "39"),
        Pair("Vampire", "41"),
    )

    val FILTER_LIST get() = AnimeFilterList(
        QueryUriSelectFilter("Type", "type", TYPE_LIST),
        QueryUriSelectFilter("Status", "status", STATUS_LIST),
        QueryUriSelectFilter("Rated", "rated", RATED_LIST),
        QueryUriSelectFilter("Score", "score", SCORE_LIST),
        QueryUriSelectFilter("Season", "season", SEASON_LIST),
        QueryUriSelectFilter("Language", "language", LANGUAGE_LIST),
        QueryUriSelectFilter("Sort", "sort", SORT_LIST),
        AnimeFilter.Separator(),
        QueryUriGroupFilter("Genres", "genres", GENRES_LIST),
    )

    data class SearchParameters(
        val type: String = "all",
        val status: String = "all",
        val rated: String = "all",
        val score: String = "all",
        val season: String = "all",
        val language: String = "all",
        val sort: String = "default",
        val genres: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        if (filters.isEmpty()) return SearchParameters()

        return SearchParameters(
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "type" }?.getValue() ?: "all",
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "status" }?.getValue() ?: "all",
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "rated" }?.getValue() ?: "all",
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "score" }?.getValue() ?: "all",
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "season" }?.getValue() ?: "all",
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "language" }?.getValue() ?: "all",
            filters.filterIsInstance<QueryUriSelectFilter>()
                .firstOrNull { it.query == "sort" }?.getValue() ?: "default",
            filters.filterIsInstance<QueryUriGroupFilter>()
                .firstOrNull { it.query == "genres" }?.getValue() ?: "",
        )
    }
}
