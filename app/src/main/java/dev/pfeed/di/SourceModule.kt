package dev.pfeed.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.pfeed.source.SourceAdapter
import dev.pfeed.source.api.ApiSourceAdapter
import dev.pfeed.source.local.LocalFilesAdapter
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
}
