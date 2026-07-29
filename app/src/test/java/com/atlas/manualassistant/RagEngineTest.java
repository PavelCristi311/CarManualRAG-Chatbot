package com.atlas.manualassistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class RagEngineTest {
    @Test
    public void alignsNaturalStateWordingAcrossMarkdownLines() {
        assertEquals(
                12,
                RagEngine.stateAlignmentScore(
                        "My check engine is on",
                        "### Engine control malfunction\n\n"
                                + "The yellow indicator light comes on."));
    }

    @Test
    public void alignsFlashingAndBlinkingStateWording() {
        assertEquals(
                12,
                RagEngine.stateAlignmentScore(
                        "The tire warning is blinking",
                        "The indicator flashes."));
    }

    @Test
    public void formatsEveryCompleteChunkWithoutMarkdownAssetNoise() {
        SearchResult first = result(
                10,
                573,
                "Checking tire inflation pressure",
                "## Important information\n"
                        + "### Checking tire inflation pressure\n"
                        + "Check tire pressure only on cold tires.");
        SearchResult second = result(
                11,
                574,
                "Checking tire inflation pressure",
                "<!-- atlas-image-id: 187 -->\n"
                        + "![Fig. 187 Tire label](manual-asset://images/tire.webp)\n"
                        + "Always use an accurate tire pressure gauge.");

        String answer = RagEngine.formatManualAnswer(List.of(first, second));

        assertTrue(answer.contains("Check tire pressure only on cold tires."));
        assertTrue(answer.contains("Always use an accurate tire pressure gauge."));
        assertTrue(answer.contains("Figure: Fig. 187 Tire label"));
        assertTrue(answer.contains("[p. 573]"));
        assertTrue(answer.contains("[p. 574]"));
        assertFalse(answer.contains("manual-asset://"));
        assertFalse(answer.contains("atlas-image-id"));
    }

    @Test
    public void doesNotTruncateLongManualContext() {
        String tail = "FINAL_UNTRUNCATED_SENTENCE.";
        String longText = "Complete instruction ".repeat(600) + tail;

        String answer = RagEngine.formatManualAnswer(
                List.of(result(20, 301, "Engine control malfunction", longText)));

        assertTrue(answer.endsWith(tail));
        assertTrue(answer.length() > 10_000);
    }

    @Test
    public void selectsStateAlignedEvidenceInsteadOfHigherRankedBroadMatch() {
        SearchResult broad = result(
                1,
                540,
                "Checking engine coolant level and topping off",
                "Fig. 182 Coolant expansion tank in the engine compartment.");
        SearchResult unrelated = result(
                2,
                490,
                "Engine control monitoring system misfire",
                "Misfires reduce power.");
        SearchResult aligned = result(
                3,
                301,
                "Engine control malfunction",
                "The yellow indicator light comes on. "
                        + "Have the engine checked immediately.");

        SearchResult selected = RagEngine.selectTarget(
                List.of(broad, unrelated, aligned),
                "My check engine is on.");

        assertEquals(301, selected.page);
    }

    @Test
    public void prefersProceduralSectionForHowToQuestion() {
        SearchResult symptom = result(
                1,
                578,
                "Tire wear and damage",
                "Pulling to one side can indicate tire damage. Check the tires.");
        SearchResult procedure = result(
                2,
                574,
                "Checking tire inflation pressure",
                "Always use an accurate tire pressure gauge.");

        SearchResult selected = RagEngine.selectTarget(
                List.of(symptom, procedure),
                "How do I check if the left tire is flat?");

        assertEquals(574, selected.page);
    }

    @Test
    public void addsDistinctEvidenceForUncoveredPartOfCompositeQuestion() {
        SearchResult pressure = result(
                1,
                574,
                "Checking tire inflation pressure",
                "Check the tire with an accurate pressure gauge.");
        SearchResult damage = result(
                2,
                578,
                "Tire wear and damage",
                "Pulling left can indicate damage.");

        SearchResult complement = RagEngine.selectComplement(
                List.of(pressure, damage),
                pressure,
                "Car pulls left. How do I check if the tire is flat?");

        assertEquals(578, complement.page);
    }

    @Test
    public void buildsSmallRelevantContextForCompanionModel() {
        SearchResult pressure = result(
                1,
                574,
                "Checking tire inflation pressure",
                "Decorative introductory wording without a useful action. "
                        + "Check all tires when cold with an accurate pressure gauge. "
                        + "Always reinstall the valve caps.");
        SearchResult damage = result(
                2,
                578,
                "Tire wear and damage",
                "If the vehicle pulls to one side, immediately reduce speed. "
                        + "Check tires and wheel rims for damage.");

        String context = RagEngine.buildSummaryContext(
                List.of(pressure, damage),
                pressure,
                "The car pulls left. How do I check if a tire is flat?");

        assertTrue(context.contains("accurate pressure gauge"));
        assertTrue(context.contains("immediately reduce speed"));
        assertTrue(context.length() <= 900);
    }

    @Test
    public void acceptsOnlyCompleteBoundedCompanionSummaries() {
        assertTrue(RagEngine.isUsableSummary(
                "Check the cold tire pressure with an accurate gauge, then inspect "
                        + "the wheel for visible damage. [p. 574]"));
        assertTrue(RagEngine.isUsableSummary(
                "Check the tires cold with an accurate gauge, then inspect the "
                        + "wheel rims for damage. [p. 574] [p. 578]"));
        assertFalse(RagEngine.isUsableSummary(
                "Check the cold tire pressure with an accurate gauge"));
        assertFalse(RagEngine.isUsableSummary(
                "I'm sorry, but I am unable to answer from these facts. [p. 574]"));
        assertFalse(RagEngine.isUsableSummary(
                "Question: How should I check tire pressure? Example facts: Check it."));
    }

    private static SearchResult result(
            long id, int page, String section, String text) {
        return new SearchResult(
                id, page, section, text, 1.0, 1, 1, 0.1f);
    }
}
