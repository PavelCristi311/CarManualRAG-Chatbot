package com.atlas.manualassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RagTimingsTest {
    @Test
    public void formatsEveryRagStageAndTotalInSecondsAndMilliseconds() {
        RagTimings timings = new RagTimings();
        timings.questionPreparationNanos = 500_000L;
        timings.embeddingNanos = 12_300_000L;
        timings.retrievalNanos = 45_600_000L;
        timings.modelGenerationNanos = 1_234_500_000L;
        timings.totalNanos = 1_500_000_000L;

        String text = timings.detailedText();

        assertTrue(text.startsWith("Response time analysis\n"));
        assertTrue(text.contains("Question preparation: 0.001 s (0.5 ms)"));
        assertTrue(text.contains("Manual fact lookup: 0.000 s (0.0 ms)"));
        assertTrue(text.contains("Query embedding: 0.012 s (12.3 ms)"));
        assertTrue(text.contains("Hybrid retrieval: 0.046 s (45.6 ms)"));
        assertTrue(text.contains("Evidence check: 0.000 s (0.0 ms)"));
        assertTrue(text.contains("Answer routing/extraction: 0.000 s (0.0 ms)"));
        assertTrue(text.contains("Image lookup: 0.000 s (0.0 ms)"));
        assertTrue(text.contains("Prompt construction: 0.000 s (0.0 ms)"));
        assertTrue(text.contains("Model generation: 1.235 s (1234.5 ms)"));
        assertTrue(text.contains("Answer validation: 0.000 s (0.0 ms)"));
        assertTrue(text.contains("Pipeline overhead: 0.207 s (207.1 ms)"));
        assertTrue(text.endsWith("Total: 1.500 s (1500.0 ms)"));
        assertEquals(1_500L, timings.totalMs());
    }
}
