package com.atlas.manualassistant;

import android.content.Context;

import java.io.File;

final class CompanionSummarizer implements AutoCloseable {
    private static final String SYSTEM_PROMPT =
            "You are Atlas, a friendly and dependable vehicle companion. Answer the "
                    + "driver's actual intent naturally, using only the supplied owner's-"
                    + "manual excerpts. Explain the useful action first, then any safety "
                    + "warnings, conditions, or follow-up steps needed for a complete "
                    + "answer. Never invent vehicle details. Do not copy input labels, "
                    + "repeat the question, use a canned opening, or apologize when the "
                    + "manual provides the answer. Do not infer a diagnosis from a "
                    + "symptom; state the manual's checks and safety actions. Cover both "
                    + "the procedure facts and related safety facts when supplied. Write "
                    + "two to five clear sentences.";
    private static final String ASSET =
            "models/qwen2.5-0.5b-instruct-q4_0.gguf";
    private static final String FILE =
            "qwen2.5-0.5b-instruct-q4_0.gguf";
    private static final long BYTES = 428_730_208L;

    static {
        System.loadLibrary("atlas-summary");
    }

    /** Installs and warms the shared Qwen model with its persistent system prompt. */
    CompanionSummarizer(Context context) throws Exception {
        File model = AssetInstaller.ensureFile(
                context, ASSET, FILE, BYTES);
        if (!nativeLoad(
                model.getAbsolutePath(),
                CpuBudget.workerThreads(),
                SYSTEM_PROMPT)) {
            throw new IllegalStateException("Unable to load companion SLM");
        }
    }

    /** Generates a grounded answer and forwards native tokens without buffering. */
    String summarize(
            String question,
            String manualContext,
            TokenListener listener,
            TimingListener timingListener) {
        if (manualContext == null || manualContext.isBlank()) return "";
        String result = nativeSummarize(
                question,
                manualContext,
                listener == null ? unused -> {} : listener,
                timingListener == null
                        ? (a, b, c, d, e, f) -> {}
                        : timingListener);
        return result == null ? "" : result.trim();
    }

    /** Releases the process-wide native model and persistent prompt context. */
    @Override
    public void close() {
        nativeClose();
    }

    /** Receives text pieces directly from native generation. */
    interface TokenListener {
        void onToken(String token);
    }

    /** Receives one native timing sample after each generation pass. */
    interface TimingListener {
        void onTiming(
                long promptFormattingNanos,
                long tokenizationNanos,
                long prefillNanos,
                long generationNanos,
                long streamingNanos,
                long finalizationNanos);
    }

    /** Loads the GGUF model and evaluates the reusable system prompt. */
    private static native boolean nativeLoad(
            String modelPath, int threads, String systemPrompt);
    /** Generates one response using the already-warm native context. */
    private static native String nativeSummarize(
            String question,
            String manualContext,
            TokenListener listener,
            TimingListener timingListener);
    /** Releases all native inference resources. */
    private static native void nativeClose();
}
