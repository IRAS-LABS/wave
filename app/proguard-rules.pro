# Room entities are read reflectively by the generated DAOs and by Converters, and the
# enum ordinals are persisted, so their identity has to survive shrinking.
-keep class com.wave.scanner.data.db.** { *; }
-keepclassmembers enum com.wave.scanner.data.db.** { *; }

# org.json is part of the platform; nothing to keep, but the Overpass importer builds
# requests reflectively-free so no extra rules are needed there.

# Compose and Room both ship consumer rules. Everything else is fair game.
-dontwarn org.slf4j.**
