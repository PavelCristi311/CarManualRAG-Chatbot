package com.atlas.manualassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RagTimingsTest {
    @Test
    public void accumulatesNativeTimingAcrossCompositeModelPasses() {
        RagTimings timings = new RagTimings();

        timings.addNativeModelTiming(1, 2, 3, 4, 5, 6);
        timings.addNativeModelTiming(10, 20, 30, 40, 50, 60);

        assertEquals(11L, timings.modelPromptFormattingNanos);
        assertEquals(22L, timings.modelTokenizationNanos);
        assertEquals(33L, timings.modelPrefillNanos);
        assertEquals(44L, timings.modelGenerationNanos);
        assertEquals(55L, timings.modelStreamingNanos);
        assertEquals(66L, timings.modelFinalizationNanos);
    }

    @Test
    public void groupsDetailedStagesIntoRagModelAndOutputCategories() {
        RagTimings timings = new RagTimings();
        timings.questionPreparationNanos = 1_000_000L;
        timings.embeddingNanos = 12_000_000L;
        timings.retrievalNanos = 46_000_000L;
        timings.evidenceCheckNanos = 1_000_000L;
        timings.answerRoutingNanos = 2_000_000L;
        timings.companionSummaryNanos = 1_000_000_000L;
        timings.modelContextPreparationNanos = 10_000_000L;
        timings.addNativeModelTiming(
                20_000_000L,
                5_000_000L,
                300_000_000L,
                600_000_000L,
                10_000_000L,
                5_000_000L);
        timings.modelValidationNanos = 20_000_000L;
        timings.answerFormattingNanos = 100_000_000L;
        timings.imageLookupNanos = 10_000_000L;
        timings.firstModelTokenNanos = 900_000_000L;
        timings.totalNanos = 1_200_000_000L;

        String text = timings.detailedText();

        assertTrue(text.startsWith("Response time analysis\n\n"));
        assertTrue(text.contains("RAG — 0.062 s (62.0 ms)"));
        assertTrue(text.contains("  Input preparation: 0.001 s (1.0 ms)"));
        assertTrue(text.contains("  Query embedding: 0.012 s (12.0 ms)"));
        assertTrue(text.contains("  Hybrid retrieval: 0.046 s (46.0 ms)"));
        assertTrue(text.contains("  Evidence validation: 0.001 s (1.0 ms)"));
        assertTrue(text.contains("  Evidence routing: 0.002 s (2.0 ms)"));
        assertTrue(text.contains("Model — 1.000 s (1000.0 ms)"));
        assertTrue(text.contains(
                "  First token latency (SEND → token): 0.900 s (900.0 ms)"));
        assertTrue(text.contains("  Context selection: 0.010 s (10.0 ms)"));
        assertTrue(text.contains("  Prompt formatting: 0.020 s (20.0 ms)"));
        assertTrue(text.contains("  Tokenization: 0.005 s (5.0 ms)"));
        assertTrue(text.contains("  Prompt prefill: 0.300 s (300.0 ms)"));
        assertTrue(text.contains("  Token generation: 0.600 s (600.0 ms)"));
        assertTrue(text.contains("  Token streaming: 0.010 s (10.0 ms)"));
        assertTrue(text.contains("  Output finalization: 0.005 s (5.0 ms)"));
        assertTrue(text.contains("  Grounding validation: 0.020 s (20.0 ms)"));
        assertTrue(text.contains("  Model orchestration: 0.030 s (30.0 ms)"));
        assertTrue(text.contains("Output — 0.138 s (138.0 ms)"));
        assertTrue(text.contains(
                "  Fallback/context formatting: 0.100 s (100.0 ms)"));
        assertTrue(text.contains("  Image lookup: 0.010 s (10.0 ms)"));
        assertTrue(text.contains(
                "  Output orchestration overhead: 0.028 s (28.0 ms)"));
        assertTrue(text.endsWith("Total: 1.200 s (1200.0 ms)"));
    }
}
