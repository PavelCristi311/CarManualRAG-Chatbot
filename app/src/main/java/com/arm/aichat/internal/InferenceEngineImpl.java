package com.arm.aichat.internal;

import android.content.Context;

public final class InferenceEngineImpl implements AutoCloseable {
    static {
        System.loadLibrary("ai-chat");
    }

    private boolean loaded;

    public InferenceEngineImpl(Context context) {
        init(context.getApplicationInfo().nativeLibraryDir);
    }

    public synchronized void loadModel(String path) {
        if (loaded) return;
        if (load(path) != 0 || prepare() != 0) {
            throw new IllegalStateException("Unable to load local GGUF model");
        }
        loaded = true;
    }

    public synchronized String answer(
            String systemPrompt, String userPrompt, int maximumTokens) {
        if (!loaded) throw new IllegalStateException("Model is not loaded");
        if (processSystemPrompt(systemPrompt) != 0) {
            throw new IllegalStateException("Unable to process system prompt");
        }
        if (processUserPrompt(userPrompt, maximumTokens) != 0) {
            throw new IllegalStateException("Unable to process user prompt");
        }
        StringBuilder answer = new StringBuilder(768);
        while (true) {
            String token = generateNextToken();
            if (token == null) break;
            answer.append(token);
        }
        return answer.toString().trim();
    }

    @Override
    public synchronized void close() {
        if (loaded) {
            unload();
            loaded = false;
        }
        shutdown();
    }

    private native void init(String nativeLibraryDirectory);
    private native int load(String modelPath);
    private native int prepare();
    private native int processSystemPrompt(String prompt);
    private native int processUserPrompt(String prompt, int predictLength);
    private native String generateNextToken();
    private native void unload();
    private native void shutdown();
}
