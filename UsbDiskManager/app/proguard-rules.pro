# ── USB Disk Manager ProGuard Rules ─────────────────────────────────────────

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Keep core models for serialization
-keep class com.usbdiskmanager.core.model.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Keep USB classes
-keep class android.hardware.usb.** { *; }

# libaums
-keep class me.jahnen.** { *; }
-keep class com.github.magnusja.libaums.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Remove logging in release
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
