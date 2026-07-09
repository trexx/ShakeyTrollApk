# R8/ProGuard rules for the app.
#
# This app uses no reflection, serialization, or JNI, and its manifest components (the
# BleForegroundService and MainActivity) are kept automatically by AGP's manifest rules.
# Compose, Coroutines, Lifecycle, and AndroidX all ship their own consumer R8 rules.
# So no custom keep rules are currently required.
#
# If R8 ever strips something used only at runtime, add a targeted keep here, e.g.:
#   -keep class com.example.bleat.ble.** { *; }
#
# Keep source line numbers for readable stack traces from release crashes:
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
