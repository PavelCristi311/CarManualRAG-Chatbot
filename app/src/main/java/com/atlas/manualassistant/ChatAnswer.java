package com.atlas.manualassistant;

import java.util.Collections;
import java.util.List;

final class ChatAnswer {
    static final String ABSTENTION =
            "I couldn't find that information in the manual.";

    final String text;
    final List<SearchResult> sources;
    final List<ManualImage> images;
    final RagTimings timings;

    /** Captures the visible answer together with its evidence and diagnostics. */
    ChatAnswer(
            String text,
            List<SearchResult> sources,
            List<ManualImage> images,
            RagTimings timings) {
        this.text = text;
        this.sources = sources == null ? Collections.emptyList() : sources;
        this.images = images == null ? Collections.emptyList() : images;
        this.timings = timings;
    }

    /** Returns the safe fallback when the manual cannot support an answer. */
    static ChatAnswer abstain(RagTimings timings, long startedNanos) {
        return new ChatAnswer(
                ABSTENTION,
                Collections.emptyList(),
                Collections.emptyList(),
                timings.finish(startedNanos));
    }
}
