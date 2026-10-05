# gomobile bindings: the Go side looks classes and methods up by name via JNI.
-keep class go.** { *; }
-keep class org.metalmind.wireplug.go.** { *; }
-keep class * implements org.metalmind.wireplug.go.engine.Host { *; }
