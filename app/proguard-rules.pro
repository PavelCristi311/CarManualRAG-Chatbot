-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.atlas.manualassistant.LlamaBridge { *; }
-keep class com.atlas.manualassistant.VectorSqliteBridge { *; }
-keep class com.arm.aichat.internal.InferenceEngineImpl { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
