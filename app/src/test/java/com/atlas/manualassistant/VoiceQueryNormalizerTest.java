package com.atlas.manualassistant;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VoiceQueryNormalizerTest {
    @Test
    public void fixesObservedAccentConfusionsInVehicleContext() {
        assertEquals(
                "Tell me the year of the car",
                VoiceQueryNormalizer.normalize("Tell me the ear of the cora"));
    }

    @Test
    public void fixesUnambiguousDomainTypos() {
        assertEquals(
                "Show me the jumper cables diagram",
                VoiceQueryNormalizer.normalize("Show me the jumper cabls diagram"));
        assertEquals(
                "What is the engine capacity",
                VoiceQueryNormalizer.normalize("What is the engin capacity"));
    }

    @Test
    public void doesNotRewriteSimilarWordsOutsideManualContext() {
        assertEquals("My ear hurts", VoiceQueryNormalizer.normalize("My ear hurts"));
    }

    @Test
    public void leavesCorrectTranscriptUnchanged() {
        assertEquals(
                "What does the warning indicator mean",
                VoiceQueryNormalizer.normalize("What does the warning indicator mean"));
    }
}
