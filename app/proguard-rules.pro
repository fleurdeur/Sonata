# Sonata R8 rules
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# kotlinx.serialization
-keep,includedescriptorclasses class com.sonata.**$$serializer { *; }
-keepclassmembers class com.sonata.** {
    *** Companion;
}
-keepclasseswithmembers class com.sonata.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# BitChord extractor (reflection-free usage)
-keep class com.sonata.core.extractor.** { *; }

# Room
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**
