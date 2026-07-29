package com.atlas.manualassistant;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class ManualRepositoryTest {
    @Test
    public void expandsEveryQueryFromSemanticSectionTitles() {
        List<String> terms = ManualRepository.expandQueryTerms(
                "My check engine is on",
                Arrays.asList(
                        "Engine control malfunction",
                        "Engine control and emission control system"),
                20);

        assertTrue(terms.contains("check"));
        assertTrue(terms.contains("engine"));
        assertTrue(terms.contains("control"));
        assertTrue(terms.contains("malfunction"));
        assertTrue(terms.contains("emission"));
    }

    @Test
    public void acceptsCloseSemanticOnlyEvidenceButRejectsWeakMatches() {
        SearchResult close = new SearchResult(
                1, 574, "Checking tire inflation pressure", "evidence",
                0.01, 1, null, 0.42f);
        SearchResult weak = new SearchResult(
                2, 200, "Unrelated", "evidence",
                0.01, 1, null, 0.70f);

        assertTrue(ManualRepository.hasStrongEvidence(
                Collections.singletonList(close)));
        assertFalse(ManualRepository.hasStrongEvidence(
                Collections.singletonList(weak)));
    }
}
