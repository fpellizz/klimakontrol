# Regole ProGuard/R8 per la build release (isMinifyEnabled = true).
# La release non è ancora attivata di default: queste regole servono quando lo sarà
# (vedi docs/play-store.md). Il debug non minifica, quindi qui non cambia nulla per la CI attuale.

# androidx.security-crypto -> Google Tink (usa reflection su algoritmi/protobuf)
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.**

# org.json è nel framework Android: nessuna regola necessaria.
# Compose e AndroidX portano già le proprie consumer rules.
