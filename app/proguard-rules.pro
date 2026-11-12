# MediaPipe keeps generated protos and JNI glue reachable via reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.javalite.**

# RootEncoder
-keep class com.pedro.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.zissha.protocol.**$$serializer { *; }
-keepclassmembers class dev.zissha.protocol.** { *** Companion; }
-keepclasseswithmembers class dev.zissha.protocol.** { kotlinx.serialization.KSerializer serializer(...); }
