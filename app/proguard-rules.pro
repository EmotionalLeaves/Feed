# SQLCipher uses JNI; keep its classes intact.
-keep class net.zetetic.database.** { *; }

# kotlinx.serialization: keep generated serializers for app models.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.pfeed.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.pfeed.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.pfeed.**$$serializer { *; }

# Retrofit service interface (Retrofit ships its own rules for the rest).
-keep,allowobfuscation interface dev.pfeed.source.api.ApiService

# Activity aliases are referenced by class name from LauncherDisguise.
-keep class dev.pfeed.MainActivity
