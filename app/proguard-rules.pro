-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.atlas.manualassistant.CompanionSummarizer { *; }
-keep class com.atlas.manualassistant.CompanionSummarizer$* { *; }
-keep class com.atlas.manualassistant.VectorSqliteBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
