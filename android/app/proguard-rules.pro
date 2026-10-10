# Keep Paho + Gson intact (reflection / callbacks).
-keep class org.eclipse.paho.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.grandpapager.** { *; }
-dontwarn org.eclipse.paho.**
