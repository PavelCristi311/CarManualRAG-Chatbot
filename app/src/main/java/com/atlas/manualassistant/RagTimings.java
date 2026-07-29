package com.atlas.manualassistant;

import java.util.Locale;

final class RagTimings {
    long questionPreparationNanos;
    long embeddingNanos;
    long retrievalNanos;
    long evidenceCheckNanos;
    long answerRoutingNanos;
    long imageLookupNanos;
    long answerFormattingNanos;
    long companionSummaryNanos;
    long modelContextPreparationNanos;
    long modelPromptFormattingNanos;
    long modelTokenizationNanos;
    long modelPrefillNanos;
    long modelGenerationNanos;
    long modelStreamingNanos;
    long modelFinalizationNanos;
    long modelValidationNanos;
    long firstModelTokenNanos;
    long totalNanos;

    /** Accumulates native timings across one or both model passes. */
    void addNativeModelTiming(
            long promptFormattingNanos,
            long tokenizationNanos,
            long prefillNanos,
            long generationNanos,
            long streamingNanos,
            long finalizationNanos) {
        modelPromptFormattingNanos += promptFormattingNanos;
        modelTokenizationNanos += tokenizationNanos;
        modelPrefillNanos += prefillNanos;
        modelGenerationNanos += generationNanos;
        modelStreamingNanos += streamingNanos;
        modelFinalizationNanos += finalizationNanos;
    }

    /** Freezes total elapsed time and returns this accumulator for fluent use. */
    RagTimings finish(long startedNanos) {
        totalNanos = System.nanoTime() - startedNanos;
        return this;
    }

    /** Formats each measured phase for on-device performance diagnostics. */
    String detailedText() {
        return "Response time analysis\n\n"
                + "RAG — " + milliseconds(ragNanos()) + "\n"
                + "  Input preparation: " + milliseconds(questionPreparationNanos) + "\n"
                + "  Query embedding: " + milliseconds(embeddingNanos) + "\n"
                + "  Hybrid retrieval: " + milliseconds(retrievalNanos) + "\n"
                + "  Evidence validation: " + milliseconds(evidenceCheckNanos) + "\n"
                + "  Evidence routing: " + milliseconds(answerRoutingNanos) + "\n\n"
                + "Model — " + milliseconds(companionSummaryNanos) + "\n"
                + "  First token latency (SEND → token): "
                + (firstModelTokenNanos > 0
                        ? milliseconds(firstModelTokenNanos)
                        : "not produced")
                + "\n  Context selection: "
                + milliseconds(modelContextPreparationNanos)
                + "\n  Prompt formatting: "
                + milliseconds(modelPromptFormattingNanos)
                + "\n  Tokenization: " + milliseconds(modelTokenizationNanos)
                + "\n  Prompt prefill: " + milliseconds(modelPrefillNanos)
                + "\n  Token generation: " + milliseconds(modelGenerationNanos)
                + "\n  Token streaming: " + milliseconds(modelStreamingNanos)
                + "\n  Output finalization: " + milliseconds(modelFinalizationNanos)
                + "\n  Grounding validation: " + milliseconds(modelValidationNanos)
                + "\n  Model orchestration: " + milliseconds(modelOverheadNanos())
                + "\n\nOutput — " + milliseconds(outputNanos()) + "\n"
                + "  Fallback/context formatting: "
                + milliseconds(answerFormattingNanos)
                + "\n  Image lookup: " + milliseconds(imageLookupNanos)
                + "\n  Output orchestration overhead: "
                + milliseconds(pipelineOverheadNanos()) + "\n\n"
                + String.format(
                        Locale.ROOT,
                        "Total: %.3f s (%.1f ms)",
                        totalNanos / 1_000_000_000.0,
                        totalNanos / 1_000_000.0);
    }

    /** Shows one duration in both human-readable seconds and precise milliseconds. */
    private static String milliseconds(long nanos) {
        return String.format(
                Locale.ROOT,
                "%.3f s (%.1f ms)",
                nanos / 1_000_000_000.0,
                nanos / 1_000_000.0);
    }

    /** Sums retrieval work that happens before answer generation. */
    private long ragNanos() {
        return questionPreparationNanos
                + embeddingNanos
                + retrievalNanos
                + evidenceCheckNanos
                + answerRoutingNanos;
    }

    /** Attributes time inside the model phase not covered by a focused timer. */
    private long modelOverheadNanos() {
        long measured = modelContextPreparationNanos
                + modelPromptFormattingNanos
                + modelTokenizationNanos
                + modelPrefillNanos
                + modelGenerationNanos
                + modelStreamingNanos
                + modelFinalizationNanos
                + modelValidationNanos;
        return Math.max(0L, companionSummaryNanos - measured);
    }

    /** Attributes uninstrumented pipeline work to final output orchestration. */
    private long pipelineOverheadNanos() {
        long measured = ragNanos()
                + companionSummaryNanos
                + answerFormattingNanos
                + imageLookupNanos;
        return Math.max(0L, totalNanos - measured);
    }

    /** Sums formatting, image resolution, and final orchestration overhead. */
    private long outputNanos() {
        return answerFormattingNanos
                + imageLookupNanos
                + pipelineOverheadNanos();
    }
}
