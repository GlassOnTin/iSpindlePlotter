# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.ispindle.plotter.**$$serializer { *; }
-keepclassmembers class com.ispindle.plotter.** {
    *** Companion;
}
-keepclasseswithmembers class com.ispindle.plotter.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.netty.**
-dontwarn org.slf4j.**
