package com.atlas.manualassistant;

import java.util.Locale;

final class RagTimings {
    long questionPreparationNanos;
    long factLookupNanos;
    long embeddingNanos;
    long retrievalNanos;
    long evidenceCheckNanos;
    long answerRoutingNanos;
    long imageLookupNanos;
    long promptConstructionNanos;
    long modelGenerationNanos;
    long answerValidationNanos;
    long totalNanos;

    RagTimings finish(long startedNanos) {
        totalNanos = System.nanoTime() - startedNanos;
        return this;
    }

    long totalMs() {
        return totalNanos / 1_000_000L;
    }

    String detailedText() {
        return "Response time analysis\n"
                + "Question preparation: " + milliseconds(questionPreparationNanos) + "\n"
                + "Manual fact lookup: " + milliseconds(factLookupNanos) + "\n"
                + "Query embedding: " + milliseconds(embeddingNanos) + "\n"
                + "Hybrid retrieval: " + milliseconds(retrievalNanos) + "\n"
                + "Evidence check: " + milliseconds(evidenceCheckNanos) + "\n"
                + "Answer routing/extraction: " + milliseconds(answerRoutingNanos) + "\n"
                + "Image lookup: " + milliseconds(imageLookupNanos) + "\n"
                + "Prompt construction: " + milliseconds(promptConstructionNanos) + "\n"
                + "Model generation: " + milliseconds(modelGenerationNanos) + "\n"
                + "Answer validation: " + milliseconds(answerValidationNanos) + "\n"
                + "Pipeline overhead: " + milliseconds(overheadNanos()) + "\n"
                + String.format(
                        Locale.ROOT,
                        "Total: %.3f s (%.1f ms)",
                        totalNanos / 1_000_000_000.0,
                        totalNanos / 1_000_000.0);
    }

    private static String milliseconds(long nanos) {
        return String.format(
                Locale.ROOT,
                "%.3f s (%.1f ms)",
                nanos / 1_000_000_000.0,
                nanos / 1_000_000.0);
    }

    private long overheadNanos() {
        long measuredPhases = questionPreparationNanos
                + factLookupNanos
                + embeddingNanos
                + retrievalNanos
                + evidenceCheckNanos
                + answerRoutingNanos
                + imageLookupNanos
                + promptConstructionNanos
                + modelGenerationNanos
                + answerValidationNanos;
        return Math.max(0L, totalNanos - measuredPhases);
    }
}
