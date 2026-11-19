# WebRTC is called from native code (JNI) by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
