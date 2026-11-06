package dev.pfeed.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ItemEntity::class,
        LibraryEntity::class,
        SignalEntity::class,
        TagAffinityEntity::class,
        CreatorAffinityEntity::class,
        FollowEntity::class,
        BlockEntity::class,
        CredentialEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun libraryDao(): LibraryDao
    abstract fun signalDao(): SignalDao
    abstract fun affinityDao(): AffinityDao
    abstract fun followDao(): FollowDao
    abstract fun blockDao(): BlockDao
    abstract fun credentialDao(): CredentialDao
}
