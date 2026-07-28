package com.atlas.manualassistant;

import java.util.Collections;
import java.util.List;

final class ChatAnswer {
    static final String ABSTENTION =
            "I couldn't find that information in the manual.";

    final String text;
    final List<SearchResult> sources;
    final List<ManualImage> images;
    final boolean abstained;
    final String reason;
    final long elapsedMs;
    final RagTimings timings;

    ChatAnswer(
            String text,
            List<SearchResult> sources,
            List<ManualImage> images,
            boolean abstained,
            String reason,
            RagTimings timings) {
        this.text = text;
        this.sources = sources == null ? Collections.emptyList() : sources;
        this.images = images == null ? Collections.emptyList() : images;
        this.abstained = abstained;
        this.reason = reason;
        this.timings = timings;
        this.elapsedMs = timings.totalMs();
    }

    static ChatAnswer abstain(
            String reason, RagTimings timings, long startedNanos) {
        return new ChatAnswer(
                ABSTENTION,
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                reason,
                timings.finish(startedNanos));
    }
}
