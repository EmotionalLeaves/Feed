package dev.pfeed.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.api.ApiSourceAdapter
import dev.pfeed.source.booru.BooruAdapter
import dev.pfeed.source.direct.DirectMediaAdapter
import dev.pfeed.source.generic.GenericJsonAdapter
import dev.pfeed.source.kemono.KemonoAdapter
import dev.pfeed.source.local.LocalFilesAdapter
import dev.pfeed.source.mastodon.MastodonAdapter
import dev.pfeed.source.reddit.RedditAdapter
import dev.pfeed.source.rss.RssAdapter

/** Register new adapters here (one @Binds @IntoSet line each). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourceModule {
    @Binds @IntoSet
    abstract fun api(adapter: ApiSourceAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun rss(adapter: RssAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun local(adapter: LocalFilesAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun direct(adapter: DirectMediaAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun reddit(adapter: RedditAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun mastodon(adapter: MastodonAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun booru(adapter: BooruAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun kemono(adapter: KemonoAdapter): SourceAdapter

    @Binds @IntoSet
    abstract fun genericJson(adapter: GenericJsonAdapter): SourceAdapter
}
