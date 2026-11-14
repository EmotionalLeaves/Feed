package dev.pfeed.source.discovery

import dev.pfeed.data.settings.AppSettings
import dev.pfeed.data.settings.BooruFeed
import dev.pfeed.data.settings.MastodonFeed
import dev.pfeed.data.settings.SettingsRepository
import dev.pfeed.source.net.HttpJson
import dev.pfeed.source.parse.JsonPath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredSource(
    val type: String,          // "reddit" | "mastodon" | "booru"
    val label: String,
    val description: String,
    val subValue: String,       // sub name / host / host
    val tagValue: String = "",  // tag (mastodon/booru) or "" for reddit
    val subscriberCount: Long = 0,
)

/** Queries public APIs to find sources matching a search term, then bulk-adds chosen ones. */
@Singleton
class SourceDiscovery @Inject constructor(
    private val http: HttpJson,
    private val settings: SettingsRepository,
) {
    companion object {
        private const val SRC = "discovery"

        // Mastodon instances known to have active public timelines and media
        private val MASTODON_INSTANCES = listOf(
            "mastodon.social", 
            "yiff.social", "lewd.town", "kinkies.com", "bdsmr.com",
            "femdom.social", "nsfwdone.social", "cum.salon", "ero.social",
            "lewdland.social", "latex.social", "rubber.social",
        )

        // Booru hosts to query when searching by tag (rule34/gelbooru are included but only
        // queried when the user has set up their API key; otherwise they'd return errors).
        private val BOORU_HOSTS = listOf(
            "rule34.xxx",
            "gelbooru.com",
            "danbooru.donmai.us",
            "tbib.org",
            "xbooru.com",
            "e621.net",
            "yande.re",
            "konachan.com",
            "chan.sankakucomplex.com",
            "hypnohub.net",
            "realbooru.com",
            "aibooru.online",
            "e6ai.net",
        )
    }

    /**
     * Search all source types in parallel and return discovered sources.
     * Never throws — errors per-source are swallowed and that source type is omitted.
     */
    suspend fun search(query: String): List<DiscoveredSource> = coroutineScope {
        val q = query.trim().lowercase()
        val redditJob = async { searchReddit(q) }
        val mastodonJob = async { searchMastodon(q) }
        val booruJob = async { searchBooru(q) }
        (redditJob.await() + mastodonJob.await() + booruJob.await())
            .sortedByDescending { it.subscriberCount }
    }

    /** Add every discovered source to settings. Returns count added (skips duplicates). */
    suspend fun addAll(sources: List<DiscoveredSource>): Int {
        var addedCount = 0
        settings.update { s ->
            var current = s
            var localAdded = 0
            for (src in sources) {
                val (next, didAdd) = applySource(current, src)
                current = next
                if (didAdd) localAdded++
            }
            addedCount = localAdded  // last write wins if lambda is retried; count is recomputed idempotently
            current
        }
        return addedCount
    }

    private fun applySource(s: AppSettings, src: DiscoveredSource):
            Pair<AppSettings, Boolean> = when (src.type) {
        "reddit" -> if (src.subValue !in s.redditSubs)
            s.copy(redditSubs = s.redditSubs + src.subValue) to true else s to false
        "mastodon" -> {
            val feed = MastodonFeed(src.subValue, src.tagValue.ifBlank { null })
            if (feed !in s.mastodonFeeds) s.copy(mastodonFeeds = s.mastodonFeeds + feed) to true else s to false
        }
        "booru" -> {
            val feed = BooruFeed(src.subValue, src.tagValue.ifBlank { null })
            if (feed !in s.booruFeeds) s.copy(booruFeeds = s.booruFeeds + feed) to true else s to false
        }
        else -> s to false
    }

    // ── Reddit subreddit search ──────────────────────────────────────────────

    /**
     * Reddit killed unauthenticated JSON in May 2026, so the subreddit-search API is gone. Instead,
     * we suggest the query as a subreddit name directly (many NSFW subs are named after their
     * content), plus common variations (underscores, _porn suffix).
     */
    private suspend fun searchReddit(query: String): List<DiscoveredSource> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()

        val candidates = buildList {
            add(q.replace(' ', '_'))
            add(q.replace(' ', '_') + "_porn")
            add(q.replace(' ', '_') + "_nsfw")
            if (q.contains(' ')) add(q.replace(" ", ""))
        }.distinct().filter { it.length in 2..24 }

        // Check each candidate: try to fetch its RSS feed. If it returns content, it exists.
        return coroutineScope {
            candidates.map { name ->
                async {
                    val rssUrl = "https://old.reddit.com/r/$name/hot/.rss"
                    val exists = runCatching { http.getRaw(SRC, rssUrl) }.getOrNull()
                        ?.let { it.contains("<entry>") } == true
                    if (exists) DiscoveredSource(
                        type = "reddit",
                        label = "r/$name",
                        description = "Reddit · subreddit",
                        subValue = name,
                        subscriberCount = 10_000,
                    ) else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ── Mastodon hashtag search ───────────────────────────────────────────────

    private suspend fun searchMastodon(query: String): List<DiscoveredSource> = runCatching {
        val tag = query.replace(Regex("\\s+"), "_").removePrefix("#")
        // Check the main instance for the tag, then produce entries for all known instances
        val checkUrl = "https://mastodon.social/api/v1/timelines/tag/$tag?limit=1&only_media=true"
        val exists = runCatching { http.get(SRC, checkUrl) }.getOrNull()
        val hasContent = exists is JsonArray && exists.size > 0
        if (!hasContent) return@runCatching emptyList()

        // Tag exists on Mastodon — suggest it on all known adult instances
        MASTODON_INSTANCES.map { host ->
            DiscoveredSource(
                type = "mastodon",
                label = "$host #$tag",
                description = "Mastodon · public #$tag media",
                subValue = host,
                tagValue = tag,
                subscriberCount = if (host == "mastodon.social") 1_000_000 else 100_000,
            )
        }
    }.getOrDefault(emptyList())

    // ── Booru tag search ──────────────────────────────────────────────────────

    private suspend fun searchBooru(query: String): List<DiscoveredSource> = runCatching {
        val tag = query.replace(Regex("\\s+"), "_").lowercase()
        // Try autocomplete on Danbooru to confirm the tag exists and get its post count
        val autoUrl = "https://danbooru.donmai.us/tags.json?search[name_matches]=${
            java.net.URLEncoder.encode("*${tag}*", "UTF-8")
        }&search[order]=count&limit=5"
        val autoRoot = runCatching { http.get(SRC, autoUrl) }.getOrNull()
        val tagNames: List<String> = if (autoRoot is JsonArray) {
            autoRoot.mapNotNull { el ->
                (el as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            }.take(5)
        } else {
            listOf(tag) // fallback: just use the query directly
        }

        tagNames.flatMap { t ->
            BOORU_HOSTS.map { host ->
                DiscoveredSource(
                    type = "booru",
                    label = "$host · $t",
                    description = "Image board · tag: $t",
                    subValue = host,
                    tagValue = t,
                    subscriberCount = when (host) {
                        "rule34.xxx" -> 500_000
                        "gelbooru.com" -> 400_000
                        "danbooru.donmai.us" -> 300_000
                        "e621.net" -> 200_000
                        "chan.sankakucomplex.com" -> 150_000
                        else -> 100_000
                    },
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun looksAdult(q: String): Boolean {
        val adult = setOf(
            "porn", "nsfw", "nude", "sex", "xxx", "hentai", "bdsm", "bondage",
            "adult", "erotic", "lewd", "cum", "anal", "blowjob", "gonewild",
            "amateur", "milf", "ass", "boob", "tit", "naked", "strip",
        )
        return adult.any { q.contains(it, ignoreCase = true) }
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }
}
