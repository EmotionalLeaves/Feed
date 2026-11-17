package dev.pfeed.domain.filter

import dev.pfeed.domain.model.MediaItem

/**
 * AI-generated pictures and clips (Stable Diffusion, NovelAI, Midjourney…), recognised by the
 * tags boards and posters give them, by AI-only boards and communities, and by titles that say so.
 * Used by the "Hide AI-generated art" setting; nothing else depends on it.
 */
object AiArt {
    /** Boards that only carry AI art. */
    val HOSTS = setOf("aibooru.online", "e6ai.net")

    private val TAGS = setOf(
        "ai", "ai_generated", "ai-generated", "aigenerated", "ai_generated_image", "ai_art", "aiart", "ai-art", "ai_artwork",
        "ai_assisted", "ai-assisted", "ai_content", "ai_composition", "ai_hentai", "aihentai", "ai_porn", "aiporn", "ai_nsfw",
        "nsfwai", "nsfw_ai", "ai_girl", "aigirl", "ai_girls", "aigirls", "ai_babe", "ai_waifu", "ai_model", "aimodel", "ai_generated_art",
        "stable_diffusion", "stablediffusion", "unstable_diffusion", "unstablediffusion", "sdxl", "novelai", "novel_ai", "nai_diffusion",
        "midjourney", "dall-e", "dalle", "dall_e", "comfyui", "pony_diffusion", "ponydiffusion", "generative_ai", "generativeai", "aigenart",
        "flux_ai", "ai_animation", "ai_video", "aivideo",
    )

    /** Subreddits and similar communities given to AI art. */
    private val ORIGIN_WORDS = listOf("aigenerated", "aiporn", "ai_porn", "aihentai", "ai_hentai", "ainsfw", "nsfwai", "aiart", "unstable_diffusion", "unstablediffusion", "stablediffusion", "aibooru", "e6ai", "aigirls", "ai_nsfw")

    private val TITLE = Regex(
        """(?i)(?:\bai[\s_-]*(?:generated|art|artwork|porn|hentai|girls?|nsfw|assisted|image|waifu)\b|#ai\b|\bstable\s*diffusion\b|\bmidjourney\b|\bnovel\s*ai\b|\bdall[\s·-]?e\b|\bsdxl\b|\bcomfyui\b)""",
    )

    fun isAi(item: MediaItem): Boolean {
        val origin = dev.pfeed.domain.reco.Spread.origin(item).lowercase()
        if (HOSTS.any { origin.contains(it) }) return true
        val community = origin.removePrefix("r/")
        if (ORIGIN_WORDS.any { community.contains(it) }) return true
        if (item.tags.any { normalize(it) in TAGS }) return true
        return item.title?.let { TITLE.containsMatchIn(it) } == true
    }

    private fun normalize(tag: String) = tag.trim().removePrefix("#").lowercase().replace(' ', '_')
}
