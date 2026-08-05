# McParty — shrink + optimize (no obfuscation)
# Main class name stays stable for plugin.yml; stack traces stay readable.

-verbose
-dontnote **
-optimizationpasses 5
-allowaccessmodification

# Requested: optimization + shrink only
-dontobfuscate

# Useful for debugging if a class is kept/removed unexpectedly
# -printseeds
# -printusage

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,PermittedSubclasses,Record,Exceptions
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Paper loads the main class by name from plugin.yml
-keep public class dev.epicc.McPartyPlugin {
    public <init>();
    public void onLoad();
    public void onEnable();
    public void onDisable();
}

-keepclassmembers class * extends org.bukkit.plugin.java.JavaPlugin {
    public void onLoad();
    public void onEnable();
    public void onDisable();
}

# Bukkit discovers @EventHandler methods via reflection
-keepclassmembers class * {
    @org.bukkit.event.EventHandler <methods>;
}

# HologramService also exposes a small API for future interaction handlers and
# runtime party scopes; keep those methods available after shrinking.
-keep public class dev.epicc.hologram.HologramService {
    public <init>(...);
    public *;
}
-keep public class dev.epicc.hologram.HologramInteractionTarget {
    public *;
}
-keep interface dev.epicc.hologram.HologramRenderer { *; }
-keep class dev.epicc.hologram.PacketTextDisplayRenderer { public <init>(); public *; }
-keepclassmembers class dev.epicc.hologram.HologramService {
    dev.epicc.hologram.HologramRenderer renderer;
}

# Enums used by the JVM / switch tables
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep directory entries (resourcepack/, etc.)
-keepdirectories

# Server / soft-depend APIs are not in the plugin jar
-dontwarn org.bukkit.**
-dontwarn org.spigotmc.**
-dontwarn io.papermc.**
-dontwarn com.destroystokyo.**
-dontwarn net.kyori.**
-dontwarn com.infernalsuite.**
-dontwarn com.flowpowered.**
-dontwarn com.github.retrooper.**
-dontwarn io.github.retrooper.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn javax.**
-dontwarn jakarta.**
-dontwarn sun.**
-dontwarn com.sun.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn org.jetbrains.annotations.**
