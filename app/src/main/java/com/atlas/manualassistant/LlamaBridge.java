package com.atlas.manualassistant;

import android.content.Context;

import com.arm.aichat.internal.InferenceEngineImpl;

import java.io.File;

final class LlamaBridge implements AutoCloseable {
    private static final long MODEL_BYTES = 268_440_192L;
    private final Context context;
    private InferenceEngineImpl engine;

    LlamaBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized String answer(String systemPrompt, String userPrompt) throws Exception {
        if (engine == null) {
            File model = AssetInstaller.ensureFile(
                    context,
                    "models/smollm2-360m-instruct-q5_0.gguf",
                    "smollm2-360m-instruct-q5_0.gguf",
                    MODEL_BYTES);
            engine = new InferenceEngineImpl(context);
            engine.loadModel(model.getAbsolutePath());
        }
        return engine.answer(systemPrompt, userPrompt, 96);
    }

    @Override
    public synchronized void close() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
