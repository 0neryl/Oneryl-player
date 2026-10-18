# Stack traces from testers stay readable; the APK is small enough without it.
-dontobfuscate

# NewPipe Extractor: Rhino runs YouTube's player JavaScript, and the time-ago
# patterns and protobuf messages are looked up reflectively.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

-dontwarn okhttp3.**
-dontwarn okio.**
