package com.oneryl.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

/**
 * Finds a track from another service on the services the app plays from:
 * searches each of them for its artists and title at once and takes the
 * result whose title, artist and length agree best. Names are compared
 * transliterated, since the same artist may be written in Cyrillic on one
 * service and in Latin on the other.
 *
 * [searches] go in order of preference: a later service wins only with a
 * clearly better match, so a song both have plays from the first.
 */
class TrackMatcher(private val searches: List<suspend (String) -> List<Track>>) {

    constructor(search: suspend (String) -> List<Track>) : this(listOf(search))

    /**
     * The best match on any service, or null if none has one. A service that
     * could not be searched only matters when no other found the track: then
     * its error is thrown, as the track may well be there.
     */
    suspend fun find(track: ImportedTrack): Track? = coroutineScope {
        val results = searches.map { search ->
            async {
                try {
                    Result.success(search(track.query))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
        var best: Scored? = null
        for (result in results) {
            val match = result.getOrNull()?.let { scored(track, it) } ?: continue
            if (best == null || match.score > best.score + PREFERENCE_MARGIN) best = match
        }
        best?.track ?: results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    }

    private class Scored(val track: Track, val score: Double)

    companion object {
        private const val THRESHOLD = 0.5
        private const val FIRST_RESULT_BONUS = 0.1
        private const val CANDIDATES = 10
        private const val OTHER_VERSION_PENALTY = 0.2
        private const val PREFERENCE_MARGIN = 0.1

        /** SoundCloud plays only this much of a Go+ track to listeners without the subscription. */
        private const val PREVIEW_SECONDS = 30L

        /** Words that mark another recording of a song; they count against it unless the original has them. */
        private val OTHER_VERSIONS = setOf(
            "live", "remix", "cover", "karaoke", "instrumental", "acoustic", "sped", "slowed", "nightcore",
            "8d", "reverb", "minus", "kaver", "zhivoe", "kontsert",
        )

        /** Words music video titles add around the song's own name. */
        private val NOISE = setOf(
            "official", "video", "audio", "lyrics", "lyric", "music", "clip", "hd", "hq", "4k", "mv",
            "visualizer", "klip", "ofitsialnyy", "premera",
        )

        fun best(track: ImportedTrack, candidates: List<Track>): Track? = scored(track, candidates)?.track

        private fun scored(track: ImportedTrack, candidates: List<Track>): Scored? =
            candidates.take(CANDIDATES)
                .mapIndexedNotNull { index, candidate ->
                    if (isPreview(track, candidate)) return@mapIndexedNotNull null
                    Scored(candidate, score(track, candidate) + if (index == 0) FIRST_RESULT_BONUS else 0.0)
                }
                .filter { it.score >= THRESHOLD }
                .maxByOrNull { it.score }

        /** SoundCloud lists a Go+ track as its 30-second preview, which is all that would play. */
        private fun isPreview(track: ImportedTrack, candidate: Track): Boolean =
            candidate.source == MusicSource.SOUNDCLOUD &&
                candidate.durationSeconds == PREVIEW_SECONDS &&
                abs(track.durationSeconds - PREVIEW_SECONDS) > 2

        /** From 0 to 1: how sure [candidate] is the same recording as [track]. */
        fun score(track: ImportedTrack, candidate: Track): Double {
            val artists = track.artists.map(::normalize).filter { it.isNotEmpty() }
            val candidateArtist = normalize(candidate.artist)
            var candidateTitle = normalize(candidate.title)
            // Music videos put the artist in the title: "Artist - Song (Official Video)".
            val artistInTitle = artists.firstOrNull { it.length >= 3 && candidateTitle.contains(it) }
            if (artistInTitle != null) candidateTitle = candidateTitle.replace(artistInTitle, " ").squeezed()

            // Without its version ("Remix", "Acoustic") a title still matches, only less well.
            val title = similarity(normalize(track.displayTitle), candidateTitle).coerceAtLeast(
                similarity(normalize(track.title), candidateTitle) - if (track.version.isNullOrBlank()) 0.0 else 0.2,
            )
            val artist = when {
                artists.isEmpty() -> 0.5
                artistInTitle != null -> 1.0
                else -> artists.maxOf { similarity(it, candidateArtist).coerceAtLeast(if (candidateArtist.contains(it)) 1.0 else 0.0) }
            }
            val sourceWords = normalize(track.displayTitle).split(' ').toSet()
            val otherVersion = candidateTitle.split(' ').any { it in OTHER_VERSIONS && it !in sourceWords }
            return 0.45 * title + 0.3 * artist + 0.25 * durationScore(track.durationSeconds, candidate.durationSeconds) -
                if (otherVersion) OTHER_VERSION_PENALTY else 0.0
        }

        private fun durationScore(a: Long, b: Long): Double {
            if (a <= 0 || b <= 0) return 0.5
            val diff = abs(a - b)
            return when {
                diff <= 2 -> 1.0
                diff <= 5 -> 0.8
                diff <= 15 -> 0.4
                diff <= 30 -> 0.1
                diff <= 60 -> 0.0
                else -> -0.6
            }
        }

        /** Dice coefficient over letter pairs; 1 when the words match, 0 when nothing does. */
        internal fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            val pairsA = pairs(a)
            val pairsB = pairs(b).toMutableList()
            if (pairsA.isEmpty() || pairsB.isEmpty()) return 0.0
            var shared = 0
            for (pair in pairsA) {
                if (pairsB.remove(pair)) shared++
            }
            return 2.0 * shared / (pairsA.size + pairs(b).size)
        }

        private fun pairs(text: String): List<String> =
            text.split(' ').filter { it.isNotEmpty() }.flatMap { word ->
                if (word.length == 1) listOf(word) else word.windowed(2)
            }

        /** Lowercase Latin letters and digits, words separated by single spaces. */
        internal fun normalize(text: String): String {
            val latin = buildString {
                for (char in text.lowercase()) append(TRANSLIT[char] ?: char.toString())
            }
            return latin.split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() && it !in NOISE && it != "feat" && it != "ft" }
                .joinToString(" ")
        }

        private fun String.squeezed() = split(' ').filter { it.isNotEmpty() }.joinToString(" ")

        private val TRANSLIT = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
            'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
            'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
            'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
            'я' to "ya",
        )
    }
}
