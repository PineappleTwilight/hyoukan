package eu.kanade.tachiyomi.ui.browse.feed

import tachiyomi.domain.manga.model.Manga

/**
 * The local half of the Feed upgrade (#107): filters, deduplication and suggestions.
 *
 * Everything here is a pure function over the feed's own results plus a snapshot of the reader's
 * library and history. No source is asked for a second page, no network call is made, and no
 * database row is written: the feature only changes how the page already on screen is presented,
 * which is also why it can be switched off without leaving anything behind.
 */
object FeedEnhancements {

    enum class LibraryFilter { ALL, NOT_IN_LIBRARY, IN_LIBRARY }

    enum class ReadFilter { ALL, UNREAD, READ }

    data class State(
        /** Feed category the visible rows are narrowed to, or null for every category. */
        val category: String? = null,
        /** Genre chip, matched case-insensitively against the entry's own genre list. */
        val genre: String? = null,
        val libraryFilter: LibraryFilter = LibraryFilter.ALL,
        val readFilter: ReadFilter = ReadFilter.ALL,
    ) {
        val isActive: Boolean
            get() = category != null || genre != null ||
                libraryFilter != LibraryFilter.ALL || readFilter != ReadFilter.ALL
    }

    /**
     * Collapses entries that describe the same series in more than one feed or duplicate row.
     *
     * Identity ignores the source, because the same series published by two feeds is exactly the
     * duplicate this is looking for. The surviving entry is the one with the newest timestamp, so
     * a newer release in a second feed is not hidden behind an older copy of the first.
     */
    fun dedupe(mangas: List<Manga>): List<Manga> {
        val byKey = LinkedHashMap<String, Manga>()
        for (manga in mangas) {
            val key = identityKey(manga)
            val existing = byKey[key]
            if (existing == null || isNewer(manga, existing)) {
                byKey[key] = manga
            }
        }
        return byKey.values.toList()
    }

    fun identityKey(manga: Manga): String =
        "${normalize(manga.title)}|${normalize(manga.author.orEmpty())}"

    fun applyFilters(
        mangas: List<Manga>,
        state: State,
        readIds: Set<Long>,
    ): List<Manga> = mangas.filter { manga ->
        val genreOk = state.genre == null ||
            manga.genre.orEmpty().any { it.equals(state.genre, ignoreCase = true) }
        val libraryOk = when (state.libraryFilter) {
            LibraryFilter.ALL -> true
            LibraryFilter.IN_LIBRARY -> manga.favorite
            LibraryFilter.NOT_IN_LIBRARY -> !manga.favorite
        }
        val readOk = when (state.readFilter) {
            ReadFilter.ALL -> true
            ReadFilter.READ -> manga.id in readIds
            ReadFilter.UNREAD -> manga.id !in readIds
        }
        genreOk && libraryOk && readOk
    }

    fun genresOf(mangas: List<Manga>): List<String> =
        mangas.flatMap { it.genre.orEmpty() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** True when [candidate] should stand for the duplicate pair. */
    private fun isNewer(candidate: Manga, existing: Manga): Boolean {
        // A library copy is the more useful one to keep: the filters and the card's own state
        // read from it, and it is the copy the reader can open without a re-fetch.
        if (candidate.favorite != existing.favorite) return candidate.favorite
        if (candidate.lastUpdate != existing.lastUpdate) return candidate.lastUpdate > existing.lastUpdate
        return candidate.dateAdded > existing.dateAdded
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /**
     * Ranks the feed's own results against the reader's library and history.
     *
     * Four signals, in the order a reader tends to trust them: a matching franchise, a similar
     * title, shared genres, and a source they already read. Each candidate lands in exactly one
     * section - its strongest signal - so the screen does not show the same cover twice.
     */
    object Suggestions {
        enum class Reason { FRANCHISE, SIMILARITY, TAG, SOURCE }

        data class Suggestion(
            val manga: Manga,
            val reason: Reason,
            /** The library/feed title or tag that produced the match, for the UI's caption. */
            val basis: String?,
        )

        data class Section(
            val reason: Reason,
            val suggestions: List<Suggestion>,
        )

        private val STOP_WORDS = setOf(
            "the", "and", "of", "a", "an", "to", "in", "is", "for", "with",
            "vol", "volume", "chapter", "ch", "part", "season",
        )

        fun build(
            candidates: List<Manga>,
            library: List<Manga>,
            historyTitles: List<String>,
            limitPerSection: Int = 8,
        ): List<Section> {
            val libraryTitles = library.map { it.title }
            val seedTitles = (historyTitles + libraryTitles).distinctBy { FeedEnhancements.normalize(it) }
            val tagAffinity = library
                .flatMap { it.genre.orEmpty() }
                .filter { it.isNotBlank() }
                .groupingBy { it.lowercase() }
                .eachCount()
            val librarySources = library.map { it.source }.toSet()

            val scored = FeedEnhancements.dedupe(candidates)
                .filterNot { it.favorite }
                .mapNotNull { manga ->
                    val tokens = tokens(manga.title)
                    val normalizedTitle = FeedEnhancements.normalize(manga.title)
                    if (tokens.isEmpty()) return@mapNotNull null

                    franchiseBasis(libraryTitles, manga.title)?.let {
                        return@mapNotNull Suggestion(manga, Reason.FRANCHISE, it) to 100.0
                    }
                    similarityBasis(seedTitles, normalizedTitle, tokens)?.let { (title, score) ->
                        return@mapNotNull Suggestion(manga, Reason.SIMILARITY, title) to score * 10
                    }
                    val sharedTags = manga.genre.orEmpty()
                        .filter { tagAffinity.containsKey(it.lowercase()) }
                        .sortedByDescending { tagAffinity[it.lowercase()] ?: 0 }
                    if (sharedTags.isNotEmpty()) {
                        val score = sharedTags.sumOf { tagAffinity[it.lowercase()] ?: 0 }.toDouble()
                        return@mapNotNull Suggestion(manga, Reason.TAG, sharedTags.first()) to score
                    }
                    if (manga.source in librarySources) {
                        return@mapNotNull Suggestion(manga, Reason.SOURCE, null) to 1.0
                    }
                    null
                }
                .sortedByDescending { it.second }

            return Reason.entries.mapNotNull { reason ->
                val suggestions = scored
                    .filter { it.first.reason == reason }
                    .take(limitPerSection)
                    .map { it.first }
                if (suggestions.isEmpty()) null else Section(reason, suggestions)
            }
        }

        /**
         * A title that starts with a library title and continues with a number, subtitle or
         * season marker is treated as the same franchise: "One Piece" and "One Piece Part 2"
         * belong together, "One Piece" and "One Punch Man" do not.
         */
        private fun franchiseBasis(libraryTitles: List<String>, title: String): String? {
            val candidate = FeedEnhancements.normalize(title)
            if (candidate.length < 4) return null
            return libraryTitles.firstOrNull { libraryTitle ->
                val base = FeedEnhancements.normalize(libraryTitle)
                base.length >= 4 &&
                    candidate != base &&
                    candidate.startsWith("$base ")
            }
        }

        private fun similarityBasis(
            seedTitles: List<String>,
            candidateTitle: String,
            candidateTokens: Set<String>,
        ): Pair<String, Double>? {
            var best: Pair<String, Double>? = null
            for (seed in seedTitles) {
                // The same series is not a recommendation for itself.
                if (FeedEnhancements.normalize(seed) == candidateTitle) continue
                val seedTokens = tokens(seed)
                if (seedTokens.isEmpty()) continue
                val shared = candidateTokens.intersect(seedTokens).size
                if (shared == 0) continue
                val union = candidateTokens.union(seedTokens).size
                val score = shared.toDouble() / union
                if (score >= 0.5 && (best == null || score > best.second)) {
                    best = seed to score
                }
            }
            return best
        }

        private fun tokens(title: String): Set<String> =
            FeedEnhancements.normalize(title)
                .split(' ')
                .filter { it.length >= 3 && it !in STOP_WORDS && it.toIntOrNull() == null }
                .toSet()
    }
}
