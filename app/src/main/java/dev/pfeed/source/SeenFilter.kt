package dev.pfeed.source

/** Answers "has the user already been shown this item?" so sources can skip it and keep the feed new. */
fun interface SeenFilter {
    fun isSeen(key: String): Boolean

    companion object {
        val NONE = SeenFilter { false }
    }
}
