# Keep kotlinx.serialization metadata for our DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.dsbot.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.dsbot.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
