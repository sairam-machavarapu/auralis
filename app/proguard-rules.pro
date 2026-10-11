# Keep generic signatures and annotations used by Room, Hilt and Media3.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Android instantiates these entry points from the manifest.
-keep class com.auralis.music.AuralisApplication { *; }
-keep class com.auralis.music.MainActivity { *; }
-keep class com.auralis.service.playback.PlaybackService { *; }

# Room's generated implementation is loaded by its database builder.
-keep class * extends androidx.room.RoomDatabase
