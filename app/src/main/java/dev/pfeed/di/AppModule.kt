package dev.pfeed.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pfeed.BuildConfig
import dev.pfeed.data.db.AppDatabase
import dev.pfeed.data.db.AffinityDao
import dev.pfeed.data.db.BlockDao
import dev.pfeed.data.db.CredentialDao
import dev.pfeed.data.db.FollowDao
import dev.pfeed.data.db.ItemDao
import dev.pfeed.data.db.LibraryDao
import dev.pfeed.data.db.SignalDao
import dev.pfeed.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") != null) chain.proceed(request)
            else chain.proceed(request.newBuilder().header("User-Agent", "PrivateFeed/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .build()

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, secrets: SecretStore): AppDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(context, AppDatabase::class.java, "pfeed.db")
            .openHelperFactory(SupportOpenHelperFactory(secrets.databasePassphrase()))
            .build()
    }

    @Provides fun itemDao(db: AppDatabase): ItemDao = db.itemDao()
    @Provides fun libraryDao(db: AppDatabase): LibraryDao = db.libraryDao()
    @Provides fun signalDao(db: AppDatabase): SignalDao = db.signalDao()
    @Provides fun affinityDao(db: AppDatabase): AffinityDao = db.affinityDao()
    @Provides fun followDao(db: AppDatabase): FollowDao = db.followDao()
    @Provides fun blockDao(db: AppDatabase): BlockDao = db.blockDao()
    @Provides fun credentialDao(db: AppDatabase): CredentialDao = db.credentialDao()
}
