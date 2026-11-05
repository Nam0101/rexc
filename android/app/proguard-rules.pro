# UniFFI talks to the Rust library through JNA by reflection.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**

# ML Kit (the QR scanner) builds its component registrars by reflection at startup; R8 full mode
# drops their no-argument constructors and the scanner's components never register.
-keep class com.google.mlkit.**Registrar { <init>(); }
